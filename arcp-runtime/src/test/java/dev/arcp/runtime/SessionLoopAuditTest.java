package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobCancel;
import dev.arcp.core.messages.JobCancelled;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.SessionBye;
import dev.arcp.core.messages.SessionClosed;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.session.SessionLoop;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Protocol-level coverage for the audit fixes (#74, #89, #90, #91, #95, #96, #108). */
class SessionLoopAuditTest {
  private static final ObjectMapper MAPPER = ArcpMapper.shared();
  private static final SessionId CLIENT_SESSION = SessionId.of("sess_client");

  @Test
  void leaseExpiryEmitsErrorFinalStatusWithLeaseExpired() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder().agent("block", "1.0.0", blockingAgent(release)).build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("block@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              LeaseConstraints.of(Instant.now().plusMillis(600)),
              null,
              null));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobError error = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(error.code()).isEqualTo(ErrorCode.LEASE_EXPIRED);
      assertThat(error.finalStatus()).isEqualTo(JobError.ERROR);
      release.countDown();
    }
  }

  @Test
  void maxRuntimeSecEmitsTimeout() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder().agent("block", "1.0.0", blockingAgent(release)).build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("block@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              1));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobError error = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
      assertThat(error.finalStatus()).isEqualTo(JobError.TIMED_OUT);
      release.countDown();
    }
  }

  @Test
  void cancelIsAcknowledgedThenErrors() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  release.await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("block@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
      h.sendJob(Message.Type.JOB_CANCEL, new JobCancel("stop"), accepted.jobId());
      JobCancelled ack = h.take(Message.Type.JOB_CANCELLED, JobCancelled.class);
      assertThat(ack.reason()).isEqualTo("stop");
      JobError error = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(error.code()).isEqualTo(ErrorCode.CANCELLED);
      release.countDown();
    }
  }

  @Test
  void cancelOfUnknownJobYieldsJobNotFound() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      h.sendJob(Message.Type.JOB_CANCEL, new JobCancel("x"), JobId.of("job_missing"));
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.JOB_NOT_FOUND);
    }
  }

  @Test
  void cancelWithoutJobIdYieldsInvalidRequest() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      h.send(Message.Type.JOB_CANCEL, new JobCancel("x"));
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
  }

  @Test
  void sessionCloseIsAcknowledged() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      h.send(Message.Type.SESSION_BYE, new SessionBye("bye"));
      SessionClosed closed = h.take(Message.Type.SESSION_CLOSED, SessionClosed.class);
      assertThat(closed.reason()).isEqualTo("bye");
    }
  }

  @Test
  void budgetRemainingGaugeDoesNotDecrementBudget() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "gauge",
                "1.0.0",
                (input, ctx) -> {
                  ctx.emit(new MetricEvent("cost.budget.remaining", new BigDecimal("4"), "usd", null));
                  ctx.emit(new MetricEvent("cost.budget.remaining", null, "usd", null));
                  ctx.authorize("fs.read", "/workspace/x");
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("gauge@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              Lease.builder().allow("fs.read", "/workspace/**").allow("cost.budget", "usd:5").build(),
              null,
              null,
              null));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      // No BUDGET_EXHAUSTED, no NPE: the job completes successfully.
      assertThat(h.take(Message.Type.JOB_RESULT, JobResult.class).finalStatus())
          .isEqualTo(JobResult.SUCCESS);
    }
  }

  @Test
  void failedAcceptDoesNotPoisonIdempotencyKey() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      ObjectNode input = JsonNodeFactory.instance.objectNode().put("x", 1);
      JobSubmit submit =
          new JobSubmit(AgentRef.parse("missing@1.0.0"), input, null, null, "retry-key", null);
      h.send(Message.Type.JOB_SUBMIT, submit);
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.AGENT_NOT_AVAILABLE);
      // Identical retry must NOT be poisoned with DUPLICATE_KEY.
      h.send(Message.Type.JOB_SUBMIT, submit);
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.AGENT_NOT_AVAILABLE);
    }
  }

  @Test
  void idempotencyFingerprintIsKeyOrderInsensitive() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build()) {
      Harness h = Harness.connect(runtime);
      h.handshake();
      ObjectNode first = JsonNodeFactory.instance.objectNode();
      first.put("b", 1);
      first.put("a", 2);
      ObjectNode second = JsonNodeFactory.instance.objectNode();
      second.put("a", 2);
      second.put("b", 1);
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(AgentRef.parse("echo@1.0.0"), first, null, null, "order", null));
      JobAccepted a1 = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(AgentRef.parse("echo@1.0.0"), second, null, null, "order", null));
      JobAccepted a2 = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(a2.jobId()).isEqualTo(a1.jobId());
    }
  }

  private static dev.arcp.runtime.agent.Agent blockingAgent(CountDownLatch release) {
    return (input, ctx) -> {
      release.await();
      return JobOutcome.Success.inline(input.payload());
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
      SessionLoop loop = runtime.accept(pair.runtime());
      assertThat(loop).isNotNull();
      return new Harness(pair.client(), probe);
    }

    SessionWelcome handshake() throws Exception {
      send(
          Message.Type.SESSION_HELLO,
          new SessionHello(
              new ClientInfo("audit-test", "1.0.0"),
              Auth.anonymous(),
              new Capabilities(
                  List.of("json"),
                  EnumSet.of(Feature.SUBSCRIBE, Feature.LIST_JOBS, Feature.COST_BUDGET),
                  null),
              null,
              null));
      return take(Message.Type.SESSION_WELCOME, SessionWelcome.class);
    }

    void send(Message.Type type, Message message) {
      sendInternal(type, message, MessageId.generate(), null);
    }

    void sendJob(Message.Type type, Message message, JobId jobId) {
      sendInternal(type, message, MessageId.generate(), jobId);
    }

    private void sendInternal(Message.Type type, Message message, MessageId id, JobId jobId) {
      client.send(
          new Envelope(
              Envelope.VERSION,
              id,
              type.wire(),
              CLIENT_SESSION,
              null,
              jobId,
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
