package dev.arcp.runtime;

import dev.arcp.Version;
import dev.arcp.auth.CredentialValidator;
import dev.arcp.auth.Identity;
import dev.arcp.auth.Principal;
import dev.arcp.capability.Capabilities;
import dev.arcp.capability.CapabilityNegotiator;
import dev.arcp.envelope.Envelope;
import dev.arcp.envelope.MessageType;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.ids.MessageId;
import dev.arcp.ids.SessionId;
import dev.arcp.messages.session.SessionMessages;
import dev.arcp.transport.Transport;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference ARCP runtime (RFC §5). Owns a single {@link Transport}, drives the
 * session handshake on a virtual thread, and dispatches subsequent envelopes to
 * per-message handlers.
 *
 * <p>
 * v0.1 scope: handshake + capability negotiation. Job/stream/subscription
 * managers are wired in later phases via setters left blank here.
 */
public final class ARCPRuntime implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(ARCPRuntime.class);

	private final Transport transport;
	private final CredentialValidator validator;
	private final Capabilities advertised;
	private final Clock clock;
	private final ExecutorService executor;
	private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.OPENING);

	@Nullable
	private volatile Principal principal;

	@Nullable
	private volatile SessionId sessionId;

	@Nullable
	private volatile Capabilities negotiated;

	public ARCPRuntime(Transport transport, CredentialValidator validator, Capabilities advertised, Clock clock) {
		this.transport = Objects.requireNonNull(transport, "transport");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.advertised = Objects.requireNonNull(advertised, "advertised");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.executor = Executors.newVirtualThreadPerTaskExecutor();
	}

	/** Begin processing inbound envelopes on a virtual thread. */
	public void start() {
		var unused = executor.submit(this::loop);
	}

	private void loop() {
		while (!Thread.currentThread().isInterrupted() && !transport.isClosed()) {
			try {
				Optional<Envelope> next = transport.receive(100, TimeUnit.MILLISECONDS);
				next.ifPresent(this::dispatch);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void dispatch(Envelope env) {
		MessageType payload = env.payload();
		SessionState current = state.get();
		if (current != SessionState.ACCEPTED && !(payload instanceof SessionMessages.SessionOpen
				|| payload instanceof SessionMessages.SessionAuthenticate
				|| payload instanceof SessionMessages.SessionClose)) {
			LOG.warn("dropping pre-handshake envelope type={} state={}", payload.type(), current);
			return;
		}
		if (payload instanceof SessionMessages.SessionOpen open) {
			handleOpen(env, open);
		} else if (payload instanceof SessionMessages.SessionClose) {
			state.set(SessionState.CLOSED);
		} else {
			LOG.debug("post-handshake message type={} (no handler in v0.1)", payload.type());
		}
	}

	private void handleOpen(Envelope env, SessionMessages.SessionOpen open) {
		try {
			Principal p = validator.validate(Objects.requireNonNull(open.credentials(), "credentials required"));
			if (open.credentials() instanceof dev.arcp.auth.Credentials.NoneCredentials && !advertised.anonymous()) {
				throw new ARCPException(ErrorCode.PERMISSION_DENIED, "anonymous capability not advertised");
			}
			Capabilities chosen = CapabilityNegotiator.intersect(advertised, open.capabilities());
			SessionId sid = SessionId.random();
			this.principal = p;
			this.sessionId = sid;
			this.negotiated = chosen;
			Identity runtimeIdentity = new Identity(Version.IMPL_KIND, Version.IMPL_VERSION, null, p.trustLevel());
			Envelope reply = new Envelope(Envelope.PROTOCOL_VERSION, MessageId.random(), "session.accepted",
					Instant.now(clock), sid, null, null, null, null, null, null, env.id(), env.id(), null, null,
					Version.IMPL_KIND, null, null, new SessionMessages.SessionAccepted(runtimeIdentity, chosen, null));
			state.set(SessionState.ACCEPTED);
			transport.send(reply);
		} catch (ARCPException e) {
			LOG.info("rejecting session.open: {}",
					java.util.Objects.requireNonNullElse(e.getMessage(), e.code().wire()));
			Envelope reject;
			if (e.code() == ErrorCode.UNAUTHENTICATED) {
				reject = simpleReply(env, "session.unauthenticated", new SessionMessages.SessionUnauthenticated(
						java.util.Objects.requireNonNullElse(e.getMessage(), e.code().wire())));
				state.set(SessionState.REJECTED);
			} else {
				reject = simpleReply(env, "session.rejected", new SessionMessages.SessionRejected(e.code(),
						java.util.Objects.requireNonNullElse(e.getMessage(), e.code().wire())));
				state.set(SessionState.REJECTED);
			}
			transport.send(reject);
		}
	}

	private Envelope simpleReply(Envelope cause, String type, MessageType payload) {
		return new Envelope(Envelope.PROTOCOL_VERSION, MessageId.random(), type, Instant.now(clock), null, null, null,
				null, null, null, null, cause.id(), cause.id(), null, null, Version.IMPL_KIND, null, null, payload);
	}

	/** @return the current session state (for tests and observability). */
	public SessionState state() {
		SessionState s = state.get();
		return s == null ? SessionState.OPENING : s;
	}

	@Nullable
	public Principal principal() {
		return principal;
	}

	@Nullable
	public SessionId sessionId() {
		return sessionId;
	}

	@Nullable
	public Capabilities negotiated() {
		return negotiated;
	}

	@Override
	public void close() {
		executor.shutdownNow();
		transport.close();
	}
}
