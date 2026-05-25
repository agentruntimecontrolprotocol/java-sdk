package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.events.ProgressEvent;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobCancel;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.JobSubscribe;
import dev.arcp.core.messages.JobSubscribed;
import dev.arcp.core.messages.JobUnsubscribe;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.SessionAck;
import dev.arcp.core.messages.SessionBye;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionJobs;
import dev.arcp.core.messages.SessionListJobs;
import dev.arcp.core.messages.SessionPing;
import dev.arcp.core.messages.SessionPong;
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

class SessionLoopProtocolTest {
  private static final ObjectMapper MAPPER = ArcpMapper.shared();
  private static final SessionId CLIENT_SESSION = SessionId.of("sess_client");

  @Test
  void directProtocolExercisesSuccessfulLifecycleListAndReplay() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "echo",
                "1.0.0",
                (input, ctx) -> {
                  ctx.authorize("fs.read", "/workspace/app/pom.xml");
                  ctx.emit(new LogEvent("info", "starting"));
                  ctx.emit(new ProgressEvent(1, 2L, "steps", "half"));
                  ctx.emit(new MetricEvent("cost.usd", new BigDecimal("2.50"), "usd", null));
                  ctx.emit(new StatusEvent("finishing", "almost done"));
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      Harness harness = Harness.connect(runtime);
      SessionWelcome welcome = harness.handshake();
      assertThat(welcome.capabilities().features())
          .contains(Feature.SUBSCRIBE, Feature.LIST_JOBS, Feature.COST_BUDGET);

      Lease lease =
          Lease.builder().allow("fs.read", "/workspace/**").allow("cost.budget", "usd:10").build();
      JobSubmit submit =
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode().put("message", "hello"),
              lease,
              LeaseConstraints.of(Instant.now().plusSeconds(60)),
              "same-payload",
              30);

      harness.send(Message.Type.JOB_SUBMIT, submit);
      JobAccepted accepted = harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(accepted.agent()).isEqualTo("echo@1.0.0");
      assertThat(accepted.budget()).containsKey("usd");
      assertThat(accepted.budget().get("usd")).isEqualByComparingTo("10");

      assertThat(harness.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind()).isEqualTo("log");
      assertThat(harness.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind())
          .isEqualTo("progress");
      assertThat(harness.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind())
          .isEqualTo("metric");
      assertThat(harness.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind())
          .isEqualTo("status");
      JobResult result = harness.take(Message.Type.JOB_RESULT, JobResult.class);
      assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
      assertThat(result.result().get("message").asText()).isEqualTo("hello");

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode().put("message", "hello"),
              lease,
              submit.leaseConstraints(),
              "same-payload",
              30));
      assertThat(harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class).jobId())
          .isEqualTo(accepted.jobId());

      harness.send(Message.Type.SESSION_ACK, new SessionAck(4));
      harness.sendWithId(
          Message.Type.SESSION_LIST_JOBS, new SessionListJobs(null, 1, null), MessageId.of("list"));
      SessionJobs jobs = harness.take(Message.Type.SESSION_JOBS, SessionJobs.class);
      assertThat(jobs.jobs()).hasSize(1);
      assertThat(jobs.jobs().getFirst().jobId()).isEqualTo(accepted.jobId());

      harness.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(accepted.jobId(), 0L, true));
      JobSubscribed subscribed = harness.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class);
      assertThat(subscribed.replayed()).isTrue();
      assertThat(subscribed.currentStatus()).isEqualTo("success");
      assertThat(harness.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind()).isEqualTo("log");
      harness.send(Message.Type.JOB_UNSUBSCRIBE, new JobUnsubscribe(accepted.jobId()));
      harness.send(Message.Type.SESSION_PING, new SessionPing("client-ping", Instant.now()));
      assertThat(harness.take(Message.Type.SESSION_PONG, SessionPong.class).pingNonce())
          .isEqualTo("client-ping");

      harness.send(Message.Type.SESSION_BYE, new SessionBye("done"));
    }
  }

  @Test
  void directProtocolExercisesFailureCancelAndInvalidRequests() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "fail",
                "1.0.0",
                (input, ctx) -> new JobOutcome.Failure(ErrorCode.INVALID_REQUEST, "bad input"))
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  release.await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      Harness harness = Harness.connect(runtime);
      harness.handshake();

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("missing@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.AGENT_NOT_AVAILABLE);

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("fail@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      assertThat(harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class).agent())
          .isEqualTo("fail@1.0.0");
      JobError failure = harness.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
      assertThat(failure.retryable()).isFalse();

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("block@1.0.0"),
              JsonNodeFactory.instance.objectNode().put("slow", true),
              null,
              null,
              null,
              null));
      JobAccepted accepted = harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
      harness.sendJob(Message.Type.JOB_CANCEL, new JobCancel("stop now"), accepted.jobId());
      JobError cancelled = harness.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(cancelled.code()).isEqualTo(ErrorCode.CANCELLED);
      assertThat(cancelled.message()).isEqualTo("stop now");
      release.countDown();

      JobId invisible = JobId.of("job_invisible");
      harness.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(invisible, 0L, true));
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.JOB_NOT_FOUND);
    }
  }

  @Test
  void directProtocolExercisesRuntimeErrorBranches() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .agent(
                "deny",
                "1.0.0",
                (input, ctx) -> {
                  ctx.authorize("fs.read", "/outside/file.txt");
                  return JobOutcome.Success.inline(input.payload());
                })
            .agent(
                "budget",
                "1.0.0",
                (input, ctx) -> {
                  ctx.emit(new MetricEvent("cost.usd", new BigDecimal("2"), "usd", null));
                  ctx.authorize("fs.read", "/workspace/file.txt");
                  return JobOutcome.Success.inline(input.payload());
                })
            .agent(
                "boom",
                "1.0.0",
                (input, ctx) -> {
                  throw new IllegalStateException("boom");
                })
            .build()) {
      Harness harness = Harness.connect(runtime);
      harness.handshake();

      harness.sendWithId(
          Message.Type.SESSION_LIST_JOBS,
          new SessionListJobs(null, 10, "#not-base64"),
          MessageId.of("bad-cursor"));
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.INVALID_REQUEST);

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              LeaseConstraints.of(Instant.now().minusSeconds(1)),
              null,
              null));
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.INVALID_REQUEST);

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@2.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.AGENT_VERSION_NOT_AVAILABLE);

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("deny@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              Lease.empty(),
              null,
              null,
              null));
      harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.PERMISSION_DENIED);

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("budget@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              Lease.builder()
                  .allow("fs.read", "/workspace/**")
                  .allow("cost.budget", "usd:1")
                  .build(),
              null,
              null,
              null));
      harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      harness.take(Message.Type.JOB_EVENT, JobEvent.class);
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.BUDGET_EXHAUSTED);

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("boom@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.INTERNAL_ERROR);

      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode().put("x", 1),
              null,
              null,
              "duplicate",
              null));
      harness.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      harness.take(Message.Type.JOB_RESULT, JobResult.class);
      harness.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode().put("x", 2),
              null,
              null,
              "duplicate",
              null));
      assertThat(harness.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.DUPLICATE_KEY);
    }
  }

  @Test
  void sessionLoopAccessorsReflectHandshakeAndShutdown() throws Exception {
    ArcpRuntime runtime = ArcpRuntime.builder().build();
    Harness harness = Harness.connect(runtime);
    SessionLoop loop = harness.loop();
    assertThat(loop.pendingKey()).startsWith("pending:");
    assertThat(loop.idOrPending()).isEqualTo(loop.pendingKey());
    assertThat(loop.phase()).isEqualTo(SessionLoop.Phase.AWAITING_HELLO);

    harness.handshake();
    assertThat(loop.phase()).isEqualTo(SessionLoop.Phase.ACTIVE);
    assertThat(loop.sessionId()).isNotNull();
    assertThat(loop.principal()).isNotNull();
    assertThat(loop.negotiated()).contains(Feature.SUBSCRIBE);

    loop.shutdown("test");
    assertThat(loop.phase()).isEqualTo(SessionLoop.Phase.CLOSED);
    assertThat(runtime.jobs()).isEmpty();
    runtime.close();
  }

  private static final class Harness {
    private final MemoryTransport client;
    private final Probe probe;
    private final SessionLoop loop;

    private Harness(MemoryTransport client, Probe probe, SessionLoop loop) {
      this.client = client;
      this.probe = probe;
      this.loop = loop;
    }

    static Harness connect(ArcpRuntime runtime) {
      MemoryTransport.Pair pair = MemoryTransport.pair();
      Probe probe = new Probe();
      pair.client().incoming().subscribe(probe);
      return new Harness(pair.client(), probe, runtime.accept(pair.runtime()));
    }

    SessionLoop loop() {
      return loop;
    }

    SessionWelcome handshake() throws Exception {
      send(
          Message.Type.SESSION_HELLO,
          new SessionHello(
              new ClientInfo("protocol-test", "1.0.0"),
              Auth.anonymous(),
              new Capabilities(
                  List.of("json"),
                  EnumSet.of(
                      Feature.SUBSCRIBE, Feature.LIST_JOBS, Feature.COST_BUDGET, Feature.ACK),
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

    void sendWithId(Message.Type type, Message message, MessageId messageId) {
      sendInternal(type, message, messageId, null);
    }

    private void sendInternal(
        Message.Type type, Message message, MessageId messageId, JobId jobId) {
      client.send(
          new Envelope(
              Envelope.VERSION,
              messageId,
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
