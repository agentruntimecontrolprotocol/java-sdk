package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobCancel;
import dev.arcp.core.messages.JobCancelled;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.SessionPing;
import dev.arcp.core.messages.SessionPong;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.session.SessionLoop;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** Deterministic heartbeat, park-expiry, watchdog, and eviction coverage via a manual scheduler. */
class SessionLoopSchedulerTest {

  private static final Set<Feature> WITH_HEARTBEAT =
      EnumSet.of(
          Feature.SUBSCRIBE,
          Feature.LIST_JOBS,
          Feature.COST_BUDGET,
          Feature.ACK,
          Feature.HEARTBEAT);

  private static JobSubmit submit(String agent, LeaseConstraints constraints, Integer maxRuntime) {
    return new JobSubmit(
        AgentRef.parse(agent),
        JsonNodeFactory.instance.objectNode(),
        Lease.builder().allow("fs.read", "/workspace/**").build(),
        constraints,
        null,
        maxRuntime);
  }

  @Test
  void heartbeatTicksPingPongAndCloseDeterministically() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .clock(clock)
            .scheduler(scheduler)
            .heartbeatIntervalSec(5)
            .resumeWindowSec(60)
            .build()) {
      scheduler.drainNew(); // idempotency prune task

      SessionHarness h = SessionHarness.connect(runtime);
      SessionWelcome welcome = h.handshake(Auth.anonymous(), WITH_HEARTBEAT);
      assertThat(welcome.heartbeatIntervalSec()).isEqualTo(5);

      SessionHarness.await(() -> !scheduler.drainNew().isEmpty() || tickOf(scheduler) != null);
      ManualScheduler.Task tick = tickOf(scheduler);
      assertThat((Object) tick).isNotNull();

      // No time has passed: neither ping nor close.
      tick.run();
      h.send(Message.Type.SESSION_PING, new SessionPing("fence1", Instant.now()));
      h.take(Message.Type.SESSION_PONG, SessionPong.class);
      assertThat(h.probe().saw(Message.Type.SESSION_PING)).isFalse();

      // One interval elapsed: the runtime pings, the client answers.
      clock.advance(Duration.ofSeconds(6));
      tick.run();
      SessionPing ping = h.take(Message.Type.SESSION_PING, SessionPing.class);
      assertThat(ping.nonce()).startsWith("p_");
      h.send(Message.Type.SESSION_PONG, new SessionPong(ping.nonce(), Instant.now()));
      h.send(Message.Type.SESSION_PING, new SessionPing("fence2", Instant.now()));
      h.take(Message.Type.SESSION_PONG, SessionPong.class);

      // Two intervals of silence: heartbeat lost, session closes.
      clock.advance(Duration.ofSeconds(11));
      tick.runIgnoringCancel();
      assertThat(h.loop().phase()).isEqualTo(SessionLoop.Phase.CLOSED);

      // A straggler tick on a closed session is a no-op.
      tick.runIgnoringCancel();
      assertThat(h.loop().phase()).isEqualTo(SessionLoop.Phase.CLOSED);
    }
  }

  private static ManualScheduler.Task tickOf(ManualScheduler scheduler) {
    return scheduler.tasks().stream()
        .skip(1) // idempotency prune
        .filter(ManualScheduler.Task::periodic)
        .findFirst()
        .orElse(null);
  }

  @Test
  void parkCancelsHeartbeatAndExpiresAfterResumeWindow() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .clock(clock)
            .scheduler(scheduler)
            .heartbeatIntervalSec(5)
            .resumeWindowSec(60)
            .build()) {
      scheduler.drainNew();

      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.bearer("parker"), WITH_HEARTBEAT);
      SessionHarness.await(
          () -> scheduler.tasks().stream().skip(1).anyMatch(ManualScheduler.Task::periodic));
      scheduler.drainNew();

      h.runtimeEndpoint().close();
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.PARKED);
      SessionHarness.await(
          () ->
              scheduler.drainNew().stream().anyMatch(t -> !t.periodic())
                  || expiryOf(scheduler) != null);
      ManualScheduler.Task expiry = expiryOf(scheduler);
      assertThat((Object) expiry).isNotNull();

      expiry.run();
      assertThat(h.loop().phase()).isEqualTo(SessionLoop.Phase.CLOSED);
    }
  }

  private static ManualScheduler.Task expiryOf(ManualScheduler scheduler) {
    return scheduler.tasks().stream()
        .filter(t -> !t.periodic() && t.delayMillis() == 60_000L && !t.isCancelled())
        .reduce((a, b) -> b)
        .orElse(null);
  }

  @Test
  void resumeWithHeartbeatCancelsParkExpiryAndReschedulesTicks() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .clock(clock)
            .scheduler(scheduler)
            .heartbeatIntervalSec(5)
            .resumeWindowSec(60)
            .build()) {
      scheduler.drainNew();

      SessionHarness h = SessionHarness.connect(runtime);
      SessionWelcome welcome = h.handshake(Auth.bearer("carol"), WITH_HEARTBEAT);
      String token = welcome.resumeToken();

      h.runtimeEndpoint().close();
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.PARKED);
      SessionHarness.await(() -> expiryOfAnyState(scheduler) != null);
      ManualScheduler.Task expiry = expiryOfAnyState(scheduler);

      SessionHarness fresh = SessionHarness.connect(runtime);
      fresh.hello(Auth.bearer("carol"), WITH_HEARTBEAT, token, 0L);
      SessionWelcome resumed = fresh.take(Message.Type.SESSION_WELCOME, SessionWelcome.class);
      assertThat(resumed.heartbeatIntervalSec()).isEqualTo(5);
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.ACTIVE);

      // The stale expiry task firing after a successful resume must be a no-op.
      expiry.runIgnoringCancel();
      assertThat(h.loop().phase()).isEqualTo(SessionLoop.Phase.ACTIVE);

      // The resumed session is reachable through the forwarder.
      fresh.send(Message.Type.SESSION_PING, new SessionPing("post-resume", Instant.now()));
      assertThat(fresh.take(Message.Type.SESSION_PONG, SessionPong.class).pingNonce())
          .isEqualTo("post-resume");
    }
  }

  private static ManualScheduler.Task expiryOfAnyState(ManualScheduler scheduler) {
    return scheduler.tasks().stream()
        .filter(t -> !t.periodic() && t.delayMillis() == 60_000L)
        .reduce((a, b) -> b)
        .orElse(null);
  }

  @Test
  void parkWithRejectingSchedulerExpiresImmediately() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder().clock(clock).scheduler(scheduler).resumeWindowSec(60).build()) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.bearer("doomed"), SessionHarness.DEFAULT_FEATURES);

      scheduler.rejecting(true);
      h.runtimeEndpoint().close();
      SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.CLOSED);
      scheduler.rejecting(false);
    }
  }

  @Test
  void leaseExpiryWatchdogTerminatesRunningJob() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    CountDownLatch started = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .clock(clock)
            .scheduler(scheduler)
            .resumeWindowSec(60)
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  new CountDownLatch(1).await(); // until interrupted
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      scheduler.drainNew();
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();

      h.send(
          Message.Type.JOB_SUBMIT,
          submit("block@1.0.0", LeaseConstraints.of(clock.instant().plusSeconds(30)), null));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      SessionHarness.await(() -> watchdog(scheduler, 30_000L) != null);

      watchdog(scheduler, 30_000L).run();
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.LEASE_EXPIRED);
      assertThat(err.finalStatus()).isEqualTo(JobError.ERROR);
    }
  }

  private static ManualScheduler.Task watchdog(ManualScheduler scheduler, long delayMillis) {
    return scheduler.tasks().stream()
        .filter(t -> !t.periodic() && t.delayMillis() == delayMillis)
        .findFirst()
        .orElse(null);
  }

  @Test
  void watchdogsOnTerminalJobAreNoopsAndEvictionRemovesJob() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .clock(clock)
            .scheduler(scheduler)
            .resumeWindowSec(60)
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build()) {
      scheduler.drainNew();
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();

      h.send(
          Message.Type.JOB_SUBMIT,
          submit("echo@1.0.0", LeaseConstraints.of(clock.instant().plusSeconds(30)), 45));
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      h.take(Message.Type.JOB_RESULT, JobResult.class);

      // Expiry (30s), max-runtime (45s), and eviction (60s) tasks all exist once terminal.
      SessionHarness.await(() -> watchdogAny(scheduler, 60_000L) != null);

      watchdogAny(scheduler, 30_000L).runIgnoringCancel(); // lease expiry on terminal job
      watchdogAny(scheduler, 45_000L).runIgnoringCancel(); // max-runtime on terminal job
      h.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      h.take(Message.Type.SESSION_PONG, SessionPong.class);
      assertThat(h.probe().saw(Message.Type.JOB_ERROR)).isFalse();

      assertThat(runtime.job(accepted.jobId())).isNotNull();
      watchdogAny(scheduler, 60_000L).run(); // eviction
      assertThat(runtime.job(accepted.jobId())).isNull();
    }
  }

  private static ManualScheduler.Task watchdogAny(ManualScheduler scheduler, long delayMillis) {
    return scheduler.tasks().stream()
        .filter(t -> !t.periodic() && t.delayMillis() == delayMillis)
        .findFirst()
        .orElse(null);
  }

  @Test
  void maxRuntimeWatchdogTimesOutRunningJob() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    CountDownLatch started = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .clock(clock)
            .scheduler(scheduler)
            .resumeWindowSec(60)
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  new CountDownLatch(1).await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      scheduler.drainNew();
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();

      h.send(Message.Type.JOB_SUBMIT, submit("block@1.0.0", null, 45));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      SessionHarness.await(() -> watchdogAny(scheduler, 45_000L) != null);

      watchdogAny(scheduler, 45_000L).run();
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.TIMEOUT);
      assertThat(err.finalStatus()).isEqualTo(JobError.TIMED_OUT);
    }
  }

  @Test
  void leaseExpiryDuringAuthorizeFailsTheJob() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .clock(clock)
            .scheduler(scheduler)
            .resumeWindowSec(60)
            .agent(
                "auth",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  release.await();
                  ctx.authorize("fs.read", "/workspace/file.txt");
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      scheduler.drainNew();
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake();

      h.send(
          Message.Type.JOB_SUBMIT,
          submit("auth@1.0.0", LeaseConstraints.of(clock.instant().plusSeconds(30)), null));
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      clock.advance(Duration.ofSeconds(120));
      release.countDown();
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.LEASE_EXPIRED);
    }
  }

  @Test
  void cancelBeforeWorkerStartsPreventsTheRunEntirely() throws Exception {
    GatedExecutor gated = new GatedExecutor();
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .workerPool(gated)
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
              null));
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      SessionHarness.await(() -> gated.submits.get() > 0);
      SessionHarness.await(() -> runtime.job(accepted.jobId()).worker() != null);

      // Cancel while the worker is still gated: the record turns terminal before runJob starts.
      h.sendJob(Message.Type.JOB_CANCEL, new JobCancel("too soon"), accepted.jobId());
      h.take(Message.Type.JOB_CANCELLED, JobCancelled.class);
      assertThat(h.take(Message.Type.JOB_ERROR, JobError.class).code())
          .isEqualTo(ErrorCode.CANCELLED);

      gated.gate.countDown();
      gated.lastDone.get(5, TimeUnit.SECONDS);

      h.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      h.take(Message.Type.SESSION_PONG, SessionPong.class);
      assertThat(h.probe().saw(Message.Type.JOB_RESULT)).isFalse();
      assertThat(runtime.job(accepted.jobId()).status())
          .isEqualTo(dev.arcp.runtime.session.JobRecord.Status.CANCELLED);
    }
  }

  /**
   * Executor whose submitted workers wait behind a gate; cancel(true) interrupts but never skips.
   */
  private static final class GatedExecutor extends AbstractExecutorService {
    final CountDownLatch gate = new CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicInteger submits =
        new java.util.concurrent.atomic.AtomicInteger();
    volatile CompletableFuture<Void> lastDone = new CompletableFuture<>();
    private final java.util.concurrent.ExecutorService real =
        Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public Future<?> submit(Runnable task) {
      CompletableFuture<Void> done = new CompletableFuture<>();
      lastDone = done;
      submits.incrementAndGet();
      Thread thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    boolean interrupted = false;
                    while (true) {
                      try {
                        gate.await();
                        break;
                      } catch (InterruptedException e) {
                        interrupted = true;
                      }
                    }
                    if (interrupted) {
                      Thread.currentThread().interrupt();
                    }
                    try {
                      task.run();
                      done.complete(null);
                    } catch (Throwable t) {
                      done.completeExceptionally(t);
                    }
                  });
      return new Future<Object>() {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
          if (mayInterruptIfRunning) {
            thread.interrupt();
          }
          return true;
        }

        @Override
        public boolean isCancelled() {
          return false;
        }

        @Override
        public boolean isDone() {
          return done.isDone();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
          return done.get();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
          return done.get(timeout, unit);
        }
      };
    }

    @Override
    public void execute(Runnable command) {
      real.execute(command);
    }

    @Override
    public void shutdown() {
      real.shutdown();
    }

    @Override
    public java.util.List<Runnable> shutdownNow() {
      return real.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return real.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return real.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return real.awaitTermination(timeout, unit);
    }
  }
}
