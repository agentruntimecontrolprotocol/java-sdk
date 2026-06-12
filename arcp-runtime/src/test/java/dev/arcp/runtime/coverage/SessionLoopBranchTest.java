package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.auth.BearerVerifier;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobCancel;
import dev.arcp.core.messages.JobCancelled;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.JobSubscribe;
import dev.arcp.core.messages.JobSubscribed;
import dev.arcp.core.messages.JobUnsubscribe;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.RuntimeInfo;
import dev.arcp.core.messages.SessionClosed;
import dev.arcp.core.messages.SessionJobs;
import dev.arcp.core.messages.SessionListJobs;
import dev.arcp.core.messages.SessionPing;
import dev.arcp.core.messages.SessionPong;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.wire.Envelope;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.session.SessionLoop;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Branch coverage for SessionLoop dispatch, auth, cancel, subscribe, and resume edges (#33). */
class SessionLoopBranchTest {

  private static JobSubmit submit(String agent) {
    return new JobSubmit(
        AgentRef.parse(agent), JsonNodeFactory.instance.objectNode(), null, null, null, null);
  }

  @Test
  void clientOnlyAndDuplicateMessagesAreIgnored() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();

      // Duplicate hello after handshake.
      h.hello(Auth.anonymous(), SessionHarness.DEFAULT_FEATURES, null, null);
      // Unsolicited pong.
      h.send(Message.Type.SESSION_PONG, new SessionPong("n1", Instant.now()));
      // Client-only payloads arriving at the runtime.
      h.send(Message.Type.SESSION_CLOSED, new SessionClosed("done"));
      h.send(Message.Type.JOB_CANCELLED, new JobCancelled("done"));
      h.send(
          Message.Type.SESSION_WELCOME,
          new SessionWelcome(
              new RuntimeInfo("x", "1"),
              null,
              null,
              null,
              new Capabilities(List.of("json"), EnumSet.noneOf(Feature.class), null)));
      h.send(
          Message.Type.JOB_ACCEPTED,
          new JobAccepted(
              JobId.of("job_x"),
              "echo@1.0.0",
              Lease.empty(),
              null,
              null,
              null,
              Instant.now(),
              null));
      h.send(
          Message.Type.JOB_EVENT,
          new JobEvent("log", Instant.now(), JsonNodeFactory.instance.objectNode()));
      h.send(
          Message.Type.JOB_RESULT,
          new JobResult(
              JobResult.SUCCESS, null, null, JsonNodeFactory.instance.objectNode(), null));
      h.send(
          Message.Type.JOB_ERROR,
          JobError.fromJson("error", ErrorCode.INTERNAL_ERROR, "x", null, null));
      h.send(
          Message.Type.JOB_SUBSCRIBED,
          new JobSubscribed(
              JobId.of("job_x"), "running", "echo@1.0.0", null, null, null, 0L, false));
      h.send(Message.Type.SESSION_JOBS, new SessionJobs(MessageId.of("req"), List.of(), null));

      // The session is still alive and responsive afterwards.
      h.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      assertThat(h.take(Message.Type.SESSION_PONG, SessionPong.class).pingNonce())
          .isEqualTo("fence");
      assertThat(h.loop().phase()).isEqualTo(SessionLoop.Phase.ACTIVE);
    }
  }

  @Test
  void malformedEnvelopesAreDroppedPreHandshakeAndRejectedWhenActive() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      SessionHarness h = SessionHarness.connect(runtime);

      ObjectNode badSubmit = JsonNodeFactory.instance.objectNode();
      badSubmit.put("agent", "echo@1.0.0");
      badSubmit.set("input", JsonNodeFactory.instance.objectNode());
      badSubmit.set(
          "lease_constraints",
          JsonNodeFactory.instance.objectNode().put("expires_at", "2030-01-01T00:00:00+02:00"));

      // Pre-handshake: malformed payload is dropped without a reply.
      h.sendRaw(envelope("job.submit", badSubmit));
      // Pre-handshake: a decodable non-hello message is dropped too.
      h.send(Message.Type.SESSION_PING, new SessionPing("early", Instant.now()));

      h.handshake();

      // Active: same malformed payload now answers INVALID_REQUEST.
      h.sendRaw(envelope("job.submit", badSubmit));
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.INVALID_REQUEST);

      // Active: an unknown message type is INVALID_REQUEST as well.
      h.sendRaw(envelope("bogus.type", JsonNodeFactory.instance.objectNode()));
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.INVALID_REQUEST);

      // No pong was ever sent for the pre-handshake ping.
      h.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      assertThat(h.take(Message.Type.SESSION_PONG, SessionPong.class).pingNonce())
          .isEqualTo("fence");
    }
  }

  private static Envelope envelope(String type, ObjectNode payload) {
    return new Envelope(
        Envelope.VERSION,
        MessageId.generate(),
        type,
        SessionId.of("sess_client"),
        null,
        null,
        null,
        payload);
  }

  @Test
  void bearerWithoutTokenIsRejected() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.hello(new Auth(Auth.BEARER, null), SessionHarness.DEFAULT_FEATURES, null, null);
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.CLOSED);
    }
  }

  @Test
  void unsupportedAuthSchemeIsRejected() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.hello(new Auth("mtls", "cert"), SessionHarness.DEFAULT_FEATURES, null, null);
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.CLOSED);
    }
  }

  @Test
  void invalidBearerTokenIsRejectedByVerifier() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .verifier(BearerVerifier.staticToken("expected", new Principal("alice")))
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.hello(Auth.bearer("wrong"), SessionHarness.DEFAULT_FEATURES, null, null);
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.CLOSED);

      SessionHarness ok = SessionHarness.connect(runtime);
      ok.handshake(Auth.bearer("expected"), SessionHarness.DEFAULT_FEATURES);
      assertThat(ok.loop().principal()).isEqualTo(new Principal("alice"));
    }
  }

  @Test
  void unnegotiatedFeaturesAreSilentlyIgnored() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      SessionWelcome welcome = h.handshake(Auth.anonymous(), EnumSet.of(Feature.ACK));
      assertThat(welcome.capabilities().features())
          .doesNotContain(Feature.LIST_JOBS, Feature.SUBSCRIBE);

      h.send(Message.Type.SESSION_LIST_JOBS, new SessionListJobs(null, 10, null));
      h.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(JobId.of("job_missing"), null, null));

      h.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      assertThat(h.take(Message.Type.SESSION_PONG, SessionPong.class).pingNonce())
          .isEqualTo("fence");
      assertThat(h.probe().saw(Message.Type.SESSION_JOBS)).isFalse();
      assertThat(h.probe().saw(Message.Type.JOB_SUBSCRIBED)).isFalse();
      assertThat(h.probe().saw(Message.Type.JOB_ERROR)).isFalse();
    }
  }

  @Test
  void listJobsAppliesWireFilters() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(Message.Type.JOB_SUBMIT, submit("echo@1.0.0"));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      h.take(Message.Type.JOB_RESULT, JobResult.class);

      h.send(
          Message.Type.SESSION_LIST_JOBS,
          new SessionListJobs(new JobFilter(List.of("success"), "echo", null), 10, null));
      assertThat(h.take(Message.Type.SESSION_JOBS, SessionJobs.class).jobs()).hasSize(1);

      h.send(
          Message.Type.SESSION_LIST_JOBS,
          new SessionListJobs(new JobFilter(List.of("running"), null, null), 10, null));
      assertThat(h.take(Message.Type.SESSION_JOBS, SessionJobs.class).jobs()).isEmpty();
    }
  }

  @Test
  void cancelBranchesCoverMissingUnknownForeignAndTerminal() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  while (!ctx.cancelled()) {
                    Thread.onSpinWait();
                  }
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      SessionHarness alice = SessionHarness.connect(runtime);
      alice.handshake();

      // Missing job_id.
      alice.send(Message.Type.JOB_CANCEL, new JobCancel("why"));
      assertThat(alice.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.INVALID_REQUEST);

      // Unknown job id.
      alice.sendJob(Message.Type.JOB_CANCEL, new JobCancel("why"), JobId.of("job_unknown"));
      assertThat(alice.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.JOB_NOT_FOUND);

      alice.send(Message.Type.JOB_SUBMIT, submit("block@1.0.0"));
      JobAccepted accepted = alice.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      // A different (anonymous) principal can neither cancel nor subscribe to it.
      SessionHarness bob = SessionHarness.connect(runtime);
      bob.handshake();
      bob.sendJob(Message.Type.JOB_CANCEL, new JobCancel("steal"), accepted.jobId());
      assertThat(bob.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.JOB_NOT_FOUND);
      bob.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(accepted.jobId(), 0L, true));
      assertThat(bob.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.JOB_NOT_FOUND);

      // Owner cancel without a reason defaults to "cancelled".
      alice.sendJob(Message.Type.JOB_CANCEL, new JobCancel(null), accepted.jobId());
      assertThat(alice.take(Message.Type.JOB_CANCELLED, JobCancelled.class).reason())
          .isEqualTo("cancelled");
      JobError cancelled = alice.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(cancelled.code()).isEqualTo(ErrorCode.CANCELLED);
      assertThat(cancelled.message()).isEqualTo("cancelled");

      // Cancelling a terminal job acknowledges idempotently.
      alice.sendJob(Message.Type.JOB_CANCEL, new JobCancel("again"), accepted.jobId());
      assertThat(alice.take(Message.Type.JOB_CANCELLED, JobCancelled.class).reason())
          .isEqualTo("again");
    }
  }

  @Test
  void subscribeFanOutAndUnsubscribeBranches() throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "stream",
                "1.0.0",
                (input, ctx) -> {
                  ctx.emit(new LogEvent("info", "e1"));
                  ctx.emit(new StatusEvent("working", "phase"));
                  gate.await();
                  ctx.emit(new LogEvent("info", "e2"));
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      SessionHarness a = SessionHarness.connect(runtime);
      a.handshake(Auth.bearer("shared"), SessionHarness.DEFAULT_FEATURES);
      SessionHarness b = SessionHarness.connect(runtime);
      b.handshake(Auth.bearer("shared"), SessionHarness.DEFAULT_FEATURES);

      a.send(Message.Type.JOB_SUBMIT, submit("stream@1.0.0"));
      JobAccepted accepted = a.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobId jobId = accepted.jobId();
      SessionHarness.await(
          () -> runtime.job(jobId) != null && runtime.job(jobId).eventHistorySize() >= 2);

      // First subscribe: no history, no from_event_seq.
      b.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(jobId, null, null));
      assertThat(b.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class).replayed()).isFalse();

      // Second subscribe (already subscribed) with history replays recorded events.
      b.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(jobId, 0L, true));
      assertThat(b.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class).replayed()).isTrue();
      assertThat(b.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind()).isEqualTo("log");
      assertThat(b.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind()).isEqualTo("status");

      // The owner can subscribe to its own job too.
      a.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(jobId, null, null));
      a.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class);

      // Live continuation fans out to the subscriber session, once each.
      gate.countDown();
      a.take(Message.Type.JOB_EVENT, JobEvent.class);
      assertThat(b.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind()).isEqualTo("log");
      a.take(Message.Type.JOB_RESULT, JobResult.class);
      assertThat(b.take(Message.Type.JOB_RESULT, JobResult.class).finalStatus())
          .isEqualTo(JobResult.SUCCESS);

      // Unsubscribe removes only this session's subscription; unknown job ids are a no-op.
      b.send(Message.Type.JOB_UNSUBSCRIBE, new JobUnsubscribe(jobId));
      b.send(Message.Type.JOB_UNSUBSCRIBE, new JobUnsubscribe(JobId.of("job_gone")));
      b.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      b.take(Message.Type.SESSION_PONG, SessionPong.class);
      SessionHarness.await(
          () -> runtime.job(jobId) == null || runtime.job(jobId).subscribers().size() == 1);
    }
  }

  @Test
  void teardownRemovesSubscribersOfClosedSession() throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  gate.await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      SessionHarness a = SessionHarness.connect(runtime);
      a.handshake(Auth.bearer("shared"), SessionHarness.DEFAULT_FEATURES);
      SessionHarness b = SessionHarness.connect(runtime);
      b.handshake(Auth.bearer("shared"), SessionHarness.DEFAULT_FEATURES);

      a.send(Message.Type.JOB_SUBMIT, submit("block@1.0.0"));
      JobId jobId = a.take(Message.Type.JOB_ACCEPTED, JobAccepted.class).jobId();

      a.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(jobId, null, null));
      a.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class);
      b.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(jobId, null, null));
      b.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class);
      SessionHarness.await(() -> runtime.job(jobId).subscribers().size() == 2);

      // Closing B unlinks only B's subscription; A (the job owner) keeps its own.
      b.loop().shutdown("test");
      SessionHarness.await(() -> runtime.job(jobId).subscribers().size() == 1);
      assertThat(runtime.job(jobId).status().terminal()).isFalse();
      gate.countDown();
      a.take(Message.Type.JOB_RESULT, JobResult.class);
    }
  }

  @Test
  void idempotentRetryReclaimsAfterJobRemoval() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();

      JobSubmit submit =
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode().put("v", 1),
              null,
              null,
              "key-90",
              null);
      h.send(Message.Type.JOB_SUBMIT, submit);
      JobAccepted first = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      h.take(Message.Type.JOB_RESULT, JobResult.class);

      // The job disappears (e.g. evicted) while the key is still claimed (#90).
      runtime.removeJob(first.jobId());

      h.send(Message.Type.JOB_SUBMIT, submit);
      JobAccepted second = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(second.jobId()).isNotEqualTo(first.jobId());
      h.take(Message.Type.JOB_RESULT, JobResult.class);
    }
  }

  @Test
  void zeroMaxRuntimeDoesNotScheduleTimeout() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              0));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(h.take(Message.Type.JOB_RESULT, JobResult.class).finalStatus())
          .isEqualTo(JobResult.SUCCESS);
    }
  }

  @Test
  void metricVariantsOnlyDecrementTrackedCostSpend() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "metrics",
                "1.0.0",
                (input, ctx) -> {
                  ctx.emit(new MetricEvent("cost.usd", new BigDecimal("2"), "usd", null));
                  ctx.emit(new MetricEvent("cost.usd", new BigDecimal("3"), null, null));
                  ctx.emit(new MetricEvent(null, new BigDecimal("3"), "usd", null));
                  ctx.emit(new MetricEvent("cost.usd", null, "usd", null));
                  ctx.emit(new MetricEvent("tokens.in", new BigDecimal("9"), "usd", null));
                  ctx.emit(
                      new MetricEvent("cost.budget.remaining", new BigDecimal("8"), "usd", null));
                  ctx.emit(new MetricEvent("cost.usd", new BigDecimal("1"), "eur", null));
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("metrics@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              Lease.builder().allow("cost.budget", "usd:10").build(),
              null,
              null,
              null));
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      h.take(Message.Type.JOB_RESULT, JobResult.class);

      // Only the first metric (cost.*, tracked unit, non-gauge) decremented the budget (#108).
      assertThat(runtime.job(accepted.jobId()).budget().remaining("usd")).isEqualByComparingTo("8");
    }
  }

  @Test
  void emitAfterTerminalIsDroppedAndCancelledReflectsTerminalStatus() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean sawCancelled = new AtomicBoolean();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "swallow",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  try {
                    new CountDownLatch(1).await(); // interrupted by cancel
                  } catch (InterruptedException ignored) {
                    // swallow: clears the interrupt flag
                  }
                  sawCancelled.set(ctx.cancelled());
                  ctx.emit(new LogEvent("info", "after terminal"));
                  done.countDown();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(Message.Type.JOB_SUBMIT, submit("swallow@1.0.0"));
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      h.sendJob(Message.Type.JOB_CANCEL, new JobCancel("stop"), accepted.jobId());
      h.take(Message.Type.JOB_CANCELLED, JobCancelled.class);
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.CANCELLED);

      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      // The interrupt flag was cleared, so cancelled() came from the terminal record status.
      assertThat(sawCancelled).isTrue();

      // Neither the post-terminal event nor a second terminal message leaked out.
      h.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      h.take(Message.Type.SESSION_PONG, SessionPong.class);
      assertThat(h.probe().saw(Message.Type.JOB_EVENT)).isFalse();
      assertThat(h.probe().saw(Message.Type.JOB_RESULT)).isFalse();
    }
  }

  @Test
  void agentThrowingInterruptedExceptionCancelsJob() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "selfinterrupt",
                "1.0.0",
                (input, ctx) -> {
                  throw new InterruptedException("self");
                })
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(Message.Type.JOB_SUBMIT, submit("selfinterrupt@1.0.0"));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.CANCELLED);
      assertThat(err.finalStatus()).isEqualTo(JobError.CANCELLED);
      assertThat(err.message()).isEqualTo("interrupted");
    }
  }

  @Test
  void agentFailureWithoutMessageUsesExceptionClassName() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "blank",
                "1.0.0",
                (input, ctx) -> {
                  throw new IllegalStateException();
                })
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(Message.Type.JOB_SUBMIT, submit("blank@1.0.0"));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
      assertThat(err.message()).isEqualTo("IllegalStateException");
    }
  }

  @Test
  void shutdownWithEvictedJobLetsWorkerFinishSilently() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean sawCancelled = new AtomicBoolean();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "evicted",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  awaitUninterruptibly(release);
                  sawCancelled.set(ctx.cancelled());
                  done.countDown();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(Message.Type.JOB_SUBMIT, submit("evicted@1.0.0"));
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      // The record disappears from the registry, then the session closes: teardown skips the
      // unknown job and the still-running worker observes cancellation via the CLOSED phase.
      var record = runtime.job(accepted.jobId());
      runtime.removeJob(accepted.jobId());
      h.loop().shutdown("test");
      assertThat(h.loop().phase()).isEqualTo(SessionLoop.Phase.CLOSED);
      release.countDown();
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sawCancelled).isTrue();
      // The worker still won the SUCCESS transition; the send was dropped on the closed session.
      SessionHarness.await(
          () -> record.status() == dev.arcp.runtime.session.JobRecord.Status.SUCCESS);
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void sendFailureWhileActiveShutsDownSession() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "emit",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  awaitUninterruptibly(release);
                  ctx.emit(new LogEvent("info", "into the void"));
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(Message.Type.JOB_SUBMIT, submit("emit@1.0.0"));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      // Kill the client endpoint: the next runtime send throws and the session shuts down.
      h.client().close();
      release.countDown();
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.CLOSED);
    }
  }

  @Test
  void resumeWithWrongPrincipalRestoresParkedSession() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      SessionHarness a = SessionHarness.connect(runtime);
      SessionWelcome welcome = a.handshake(Auth.bearer("alice"), SessionHarness.DEFAULT_FEATURES);
      String token = welcome.resumeToken();

      a.runtimeEndpoint().close();
      SessionHarness.awaitPhase(a.loop(), SessionLoop.Phase.PARKED);

      // A different principal presenting the token is rejected; the parked session is put back.
      SessionHarness thief = SessionHarness.connect(runtime);
      thief.hello(Auth.bearer("mallory"), SessionHarness.DEFAULT_FEATURES, token, 0L);
      assertThat(thief.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.RESUME_WINDOW_EXPIRED);
      SessionHarness.awaitPhase(thief.loop(), SessionLoop.Phase.CLOSED);
      assertThat(a.loop().phase()).isEqualTo(SessionLoop.Phase.PARKED);

      // The legitimate owner resumes without a last_event_seq; inbound is then delegated.
      SessionHarness c = SessionHarness.connect(runtime);
      c.hello(Auth.bearer("alice"), SessionHarness.DEFAULT_FEATURES, token, null);
      SessionWelcome resumed = c.take(Message.Type.SESSION_WELCOME, SessionWelcome.class);
      assertThat(resumed.resumeToken()).isEqualTo(token);
      assertThat(a.loop().phase()).isEqualTo(SessionLoop.Phase.ACTIVE);

      c.send(Message.Type.SESSION_PING, new SessionPing("via-delegate", Instant.now()));
      assertThat(c.take(Message.Type.SESSION_PONG, SessionPong.class).pingNonce())
          .isEqualTo("via-delegate");

      // Dropping the forwarder's transport re-parks the resumed session.
      c.runtimeEndpoint().close();
      SessionHarness.awaitPhase(a.loop(), SessionLoop.Phase.PARKED);

      // A second drop on an already-parked session closes it for good.
      a.loop().onComplete();
      SessionHarness.awaitPhase(a.loop(), SessionLoop.Phase.CLOSED);
    }
  }

  @Test
  void preHandshakeTransportDropClosesImmediately() throws Exception {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.runtimeEndpoint().close();
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.CLOSED);
    }
  }

  @Test
  void submitWithConstraintsButNoExpiryIsAccepted() throws Exception {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();
      h.send(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("echo@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              dev.arcp.core.lease.LeaseConstraints.none(),
              null,
              null));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(h.take(Message.Type.JOB_RESULT, JobResult.class).finalStatus())
          .isEqualTo(JobResult.SUCCESS);
    }
  }
}
