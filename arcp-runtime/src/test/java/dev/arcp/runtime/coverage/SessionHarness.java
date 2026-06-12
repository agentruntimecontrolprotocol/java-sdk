package dev.arcp.runtime.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.session.SessionLoop;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Client-side harness over {@link MemoryTransport#pair()} mirroring the protocol-test pattern. */
final class SessionHarness {

  static final ObjectMapper MAPPER = ArcpMapper.shared();
  static final Set<Feature> DEFAULT_FEATURES =
      EnumSet.of(Feature.SUBSCRIBE, Feature.LIST_JOBS, Feature.COST_BUDGET, Feature.ACK);

  private final MemoryTransport client;
  private final MemoryTransport runtimeEndpoint;
  private final Probe probe;
  private final SessionLoop loop;

  private SessionHarness(
      MemoryTransport client, MemoryTransport runtimeEndpoint, Probe probe, SessionLoop loop) {
    this.client = client;
    this.runtimeEndpoint = runtimeEndpoint;
    this.probe = probe;
    this.loop = loop;
  }

  static SessionHarness connect(ArcpRuntime runtime) {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    Probe probe = new Probe();
    pair.client().incoming().subscribe(probe);
    return new SessionHarness(pair.client(), pair.runtime(), probe, runtime.accept(pair.runtime()));
  }

  SessionLoop loop() {
    return loop;
  }

  MemoryTransport client() {
    return client;
  }

  MemoryTransport runtimeEndpoint() {
    return runtimeEndpoint;
  }

  Probe probe() {
    return probe;
  }

  SessionWelcome handshake() throws Exception {
    return handshake(Auth.anonymous(), DEFAULT_FEATURES);
  }

  SessionWelcome handshake(Auth auth, Set<Feature> features) throws Exception {
    hello(auth, features, null, null);
    return take(Message.Type.SESSION_WELCOME, SessionWelcome.class);
  }

  void hello(Auth auth, Set<Feature> features, String resumeToken, Long lastEventSeq) {
    send(
        Message.Type.SESSION_HELLO,
        new SessionHello(
            new ClientInfo("coverage-test", "1.0.0"),
            auth,
            new Capabilities(List.of("json"), features, null),
            resumeToken,
            lastEventSeq));
  }

  void send(Message.Type type, Message message) {
    sendInternal(type, message, MessageId.generate(), null);
  }

  void sendJob(Message.Type type, Message message, JobId jobId) {
    sendInternal(type, message, MessageId.generate(), jobId);
  }

  void sendRaw(Envelope envelope) {
    client.send(envelope);
  }

  private void sendInternal(Message.Type type, Message message, MessageId messageId, JobId jobId) {
    client.send(
        new Envelope(
            Envelope.VERSION,
            messageId,
            type.wire(),
            SessionId.of("sess_client"),
            null,
            jobId,
            null,
            Messages.encodePayload(MAPPER, message)));
  }

  <T extends Message> T take(Message.Type type, Class<T> messageClass) throws Exception {
    return messageClass.cast(Messages.decode(MAPPER, probe.take(type)));
  }

  Envelope takeEnvelope(Message.Type type) throws InterruptedException {
    return probe.take(type);
  }

  static void awaitPhase(SessionLoop loop, SessionLoop.Phase target) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (loop.phase() == target) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("expected phase " + target + " but was " + loop.phase());
  }

  static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("timed out awaiting condition");
  }

  static final class Probe implements Flow.Subscriber<Envelope> {
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

    /** True if an envelope of {@code type} is sitting in the backlog or queue right now. */
    boolean saw(Message.Type type) {
      if (backlog.stream().anyMatch(e -> e.type().equals(type.wire()))) {
        return true;
      }
      return envelopes.stream().anyMatch(e -> e.type().equals(type.wire()));
    }

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
