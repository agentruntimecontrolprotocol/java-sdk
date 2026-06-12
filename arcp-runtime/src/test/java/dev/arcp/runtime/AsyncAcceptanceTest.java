package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionPing;
import dev.arcp.core.messages.SessionPong;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.IssuedCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * §6.4/#109: credential issuance must not block the session dispatch thread. Heartbeats keep
 * flowing while a slow provisioner is in flight, and {@code job.accepted} preserves submit order
 * even when an earlier submission's issuance resolves after a later one.
 */
class AsyncAcceptanceTest {
  private static final ObjectMapper MAPPER = ArcpMapper.shared();
  private static final SessionId CLIENT_SESSION = SessionId.of("sess_async_accept");

  @Test
  void pingsAnsweredWhileIssuanceInFlight() throws Exception {
    CompletableFuture<List<IssuedCredential>> issuance = new CompletableFuture<>();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .heartbeatIntervalSec(1)
            .credentialProvisioner(provisioner(ignored -> issuance))
            .credentialRevocationStore(new InMemoryCredentialRevocationStore())
            .build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();

      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));

      // While issuance is parked, keep pinging past 2x the heartbeat interval. Every ping must be
      // answered: a blocked dispatch thread would leave these unanswered and the runtime's own
      // heartbeat watchdog would reap the session as HEARTBEAT_LOST (#109).
      long deadline = System.nanoTime() + Duration.ofMillis(2_500).toNanos();
      while (System.nanoTime() < deadline) {
        h.send(Message.Type.SESSION_PING, new SessionPing("p_async", Instant.now()));
        SessionPong pong = h.take(Message.Type.SESSION_PONG, SessionPong.class);
        assertThat(pong.pingNonce()).isEqualTo("p_async");
        Thread.sleep(250);
      }

      // The session survived a provisioner stall > 2x interval: completing issuance still yields
      // the acceptance on the live session.
      issuance.complete(List.of(issued("cred_slow")));
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(accepted.credentials())
          .extracting(c -> c.id().value())
          .containsExactly("cred_slow");
    }
  }

  @Test
  void acceptedPreservesSubmitOrderAcrossSlowIssuance() throws Exception {
    CompletableFuture<List<IssuedCredential>> slow = new CompletableFuture<>();
    AtomicInteger calls = new AtomicInteger();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("first", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .agent("second", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .credentialProvisioner(
                provisioner(
                    ignored ->
                        calls.getAndIncrement() == 0
                            ? slow
                            : CompletableFuture.completedFuture(List.of(issued("cred_fast")))))
            .credentialRevocationStore(new InMemoryCredentialRevocationStore())
            .build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();

      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("first@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("second@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));

      // The second submission's issuance is already complete, but its acceptance must wait for the
      // first: clients correlate job.accepted to pending submits FIFO.
      slow.complete(List.of(issued("cred_slow")));
      JobAccepted first = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobAccepted second = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(first.agent()).startsWith("first@");
      assertThat(second.agent()).startsWith("second@");
    }
  }

  private static IssuedCredential issued(String id) {
    return new IssuedCredential(
        new Credential(
            CredentialId.of(id),
            CredentialScheme.BEARER,
            "v_" + id,
            "https://upstream",
            null,
            null),
        null);
  }

  private static CredentialProvisioner provisioner(
      java.util.function.Function<Object, CompletableFuture<List<IssuedCredential>>> issue) {
    return new CredentialProvisioner() {
      @Override
      public CompletableFuture<List<IssuedCredential>> issue(
          dev.arcp.core.lease.Lease lease,
          dev.arcp.core.lease.LeaseConstraints constraints,
          dev.arcp.runtime.agent.JobContext ctx) {
        return issue.apply(lease);
      }

      @Override
      public CompletableFuture<Void> revoke(CredentialId id) {
        return CompletableFuture.completedFuture(null);
      }
    };
  }

  private static final class Harness {
    private final MemoryTransport client;
    private final Probe probe;

    private Harness(MemoryTransport client, Probe probe) {
      this.client = client;
      this.probe = probe;
    }

    static Harness connect(ArcpRuntime runtime) {
      MemoryTransport.Pair pair = MemoryTransport.pair();
      Probe probe = new Probe();
      pair.client().incoming().subscribe(probe);
      assertThat(runtime.accept(pair.runtime())).isNotNull();
      return new Harness(pair.client(), probe);
    }

    SessionWelcome handshake() throws Exception {
      send(
          Message.Type.SESSION_HELLO,
          new SessionHello(
              new ClientInfo("async-accept-test", "1.0.0"),
              Auth.anonymous(),
              new Capabilities(
                  List.of("json"),
                  EnumSet.of(Feature.PROVISIONED_CREDENTIALS, Feature.HEARTBEAT),
                  null),
              null,
              null));
      return take(Message.Type.SESSION_WELCOME, SessionWelcome.class);
    }

    void send(Message.Type type, Message message) {
      client.send(
          new Envelope(
              Envelope.VERSION,
              MessageId.generate(),
              type.wire(),
              CLIENT_SESSION,
              null,
              null,
              null,
              Messages.encodePayload(MAPPER, message)));
    }

    <T extends Message> T take(Message.Type type, Class<T> messageClass) throws Exception {
      return messageClass.cast(Messages.decode(MAPPER, probe.take(type)));
    }
  }

  private static final class Probe implements Flow.Subscriber<Envelope> {
    private final BlockingQueue<Envelope> envelopes = new LinkedBlockingQueue<>();
    private final Queue<Envelope> backlog = new ArrayDeque<>();

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(Envelope item) {
      envelopes.add(item);
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}

    Envelope take(Message.Type type) throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline) {
        for (Envelope existing : List.copyOf(backlog)) {
          if (existing.type().equals(type.wire())) {
            backlog.remove(existing);
            return existing;
          }
        }
        long remaining = deadline - System.nanoTime();
        Envelope envelope = envelopes.poll(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
        if (envelope == null) {
          break;
        }
        if (envelope.type().equals(type.wire())) {
          return envelope;
        }
        backlog.add(envelope);
      }
      throw new AssertionError("timed out waiting for " + type.wire() + "; backlog=" + backlog);
    }
  }
}
