package dev.arcp.client;

import dev.arcp.auth.Credentials;
import dev.arcp.auth.Identity;
import dev.arcp.capability.Capabilities;
import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.ids.MessageId;
import dev.arcp.messages.session.SessionMessages;
import dev.arcp.transport.Transport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Reference client (RFC §5, §8). Performs the session handshake against a
 * {@link Transport} and exposes the negotiated
 * {@link SessionMessages.SessionAccepted} to the caller. Subsequent phases will
 * extend with {@code invoke}, {@code subscribe}, and handler registration.
 */
public final class ARCPClient {

	private final Transport transport;
	private final Clock clock;

	public ARCPClient(Transport transport, Clock clock) {
		this.transport = Objects.requireNonNull(transport, "transport");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Send {@code session.open} and block until the runtime answers. Returns the
	 * accepted payload on success; throws on rejection/unauthenticated/timeout.
	 */
	public SessionMessages.SessionAccepted openSession(Credentials credentials, @Nullable Identity client,
			Capabilities capabilities, Duration timeout) throws InterruptedException {
		Objects.requireNonNull(credentials, "credentials");
		Objects.requireNonNull(capabilities, "capabilities");
		Objects.requireNonNull(timeout, "timeout");
		SessionMessages.SessionOpen open = new SessionMessages.SessionOpen(Envelope.PROTOCOL_VERSION, credentials,
				client, capabilities);
		Envelope env = new Envelope(Envelope.PROTOCOL_VERSION, MessageId.random(), "session.open", Instant.now(clock),
				null, null, null, null, null, null, null, null, null, null, null, "client", null, null, open);
		transport.send(env);

		long deadlineNanos = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadlineNanos) {
			long remaining = Math.max(1, deadlineNanos - System.nanoTime());
			Optional<Envelope> received = transport.receive(remaining, TimeUnit.NANOSECONDS);
			if (received.isEmpty()) {
				break;
			}
			Envelope r = received.get();
			if (r.payload() instanceof SessionMessages.SessionAccepted accepted) {
				return accepted;
			}
			if (r.payload() instanceof SessionMessages.SessionRejected rejected) {
				throw new ARCPException(rejected.code(), rejected.message());
			}
			if (r.payload() instanceof SessionMessages.SessionUnauthenticated unauth) {
				throw new ARCPException(ErrorCode.UNAUTHENTICATED, unauth.reason());
			}
		}
		throw new ARCPException(ErrorCode.DEADLINE_EXCEEDED, "session.accepted not received in " + timeout);
	}
}
