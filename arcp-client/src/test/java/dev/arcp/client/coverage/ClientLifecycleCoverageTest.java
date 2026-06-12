package dev.arcp.client.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.Page;
import dev.arcp.client.ResultStream;
import dev.arcp.client.Session;
import dev.arcp.client.SubscribeOptions;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.Events;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.ResultId;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.core.messages.JobSummary;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.RuntimeInfo;
import dev.arcp.core.messages.SessionAck;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionJobs;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.wire.Envelope;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Branch coverage for {@link ArcpClient} lifecycle paths: blocking submit guards and timeouts,
 * list_jobs pagination and failure modes, subscription bookkeeping, close idempotency, and the
 * ack/heartbeat maintenance tasks (invoked directly so no test ever sleeps through an interval).
 */
class ClientLifecycleCoverageTest {

  private static ObjectNode obj() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static SessionWelcome welcome(Set<Feature> features) {
    return new SessionWelcome(
        new RuntimeInfo("rt", "1.0.0"),
        null,
        null,
        null,
        new Capabilities(List.of("json"), features, null));
  }

  private static ArcpClient connect(FakeTransport fake, ArcpClient.Builder builder)
      throws Exception {
    ArcpClient client = builder.build();
    CompletableFuture<Session> future = client.connect();
    fake.awaitSent("session.hello");
    fake.deliver(Message.Type.SESSION_WELCOME, welcome(EnumSet.of(Feature.ACK)));
    future.get(2, TimeUnit.SECONDS);
    return client;
  }

  private static ArcpClient connect(FakeTransport fake) throws Exception {
    return connect(fake, ArcpClient.builder(fake).ackInterval(Duration.ofHours(1)));
  }

  @Test
  void blockingSubmitFromDispatchThreadFailsFast() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake)) {
      AtomicReference<Throwable> guardTrip = new AtomicReference<>();
      CompletableFuture<JobHandle> pending =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      pending.thenAccept(
          handle -> {
            try {
              client.submit(ArcpClient.jobSubmit("echo@1.0.0", obj()));
            } catch (Throwable t) {
              guardTrip.set(t);
            }
          });
      fake.awaitSent("job.submit");
      // Delivered on the test thread, so the callback runs inside dispatch.
      fake.deliver(
          Message.Type.JOB_ACCEPTED,
          new dev.arcp.core.messages.JobAccepted(
              JobId.of("job_guard"),
              "echo@1.0.0",
              dev.arcp.core.lease.Lease.empty(),
              null,
              null,
              null,
              Instant.now(),
              null));
      assertThat(guardTrip.get())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must not be called from an event/result callback");
    }
  }

  @Test
  void blockingSubmitTimesOutAndPrunesOnlyItsPendingEntry() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client =
        connect(
            fake,
            ArcpClient.builder(fake)
                .ackInterval(Duration.ofHours(1))
                .submitTimeout(Duration.ofMillis(150)));
    // A second in-flight submit ensures the timeout prune inspects a non-matching entry too.
    CompletableFuture<JobHandle> other =
        client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
    assertThatThrownBy(() -> client.submit(ArcpClient.jobSubmit("echo@1.0.0", obj())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("submit timed out")
        .hasCauseInstanceOf(TimeoutException.class);
    assertThat(other).isNotDone();
    client.close();
  }

  @Test
  void blockingSubmitSurfacesInterruption() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake)) {
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      Thread submitter =
          new Thread(
              () -> {
                try {
                  client.submit(ArcpClient.jobSubmit("echo@1.0.0", obj()));
                } catch (Throwable t) {
                  thrown.set(t);
                }
              });
      submitter.start();
      fake.awaitSent("job.submit");
      submitter.interrupt();
      submitter.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(thrown.get())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("submit interrupted");
    }
  }

  @Test
  void blockingSubmitWrapsRejectionCause() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake)) {
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      Thread submitter =
          new Thread(
              () -> {
                try {
                  client.submit(ArcpClient.jobSubmit("echo@1.0.0", obj()), null);
                } catch (Throwable t) {
                  thrown.set(t);
                }
              });
      submitter.start();
      Envelope submitEnv = fake.awaitSent("job.submit");
      fake.deliver(
          Message.Type.JOB_ERROR,
          new JobError(JobError.ERROR, ErrorCode.PERMISSION_DENIED, "nope", false, null),
          null,
          null,
          submitEnv.id());
      submitter.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(thrown.get())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(PermissionDeniedException.class);
    }
  }

  @Test
  void listJobsPaginatesAndIgnoresUnknownResponses() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake)) {
      // A session.jobs response nobody asked for is dropped.
      fake.deliver(
          Message.Type.SESSION_JOBS, new SessionJobs(MessageId.generate(), List.of(), null));

      AtomicReference<Object> outcome = new AtomicReference<>();
      CountDownLatch finished = new CountDownLatch(1);
      Thread lister =
          new Thread(
              () -> {
                try {
                  outcome.set(client.listJobs(JobFilter.all(), 10, null));
                } catch (Exception e) {
                  outcome.set(e);
                } finally {
                  finished.countDown();
                }
              });
      lister.start();
      Envelope request = fake.awaitSent("session.list_jobs");
      JobSummary summary =
          new JobSummary(
              JobId.of("job_1"), "echo@1.0.0", "running", null, null, Instant.now(), null, 3L);
      fake.deliver(
          Message.Type.SESSION_JOBS, new SessionJobs(request.id(), List.of(summary), "cursor-2"));
      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
      @SuppressWarnings("unchecked")
      Page<JobSummary> page = (Page<JobSummary>) outcome.get();
      assertThat(page.items()).hasSize(1);
      assertThat(page.hasNext()).isTrue();
      assertThat(page.nextCursor()).isEqualTo("cursor-2");

      // The filter-only overload returns the terminal page.
      AtomicReference<Object> secondOutcome = new AtomicReference<>();
      CountDownLatch secondFinished = new CountDownLatch(1);
      Thread secondLister =
          new Thread(
              () -> {
                try {
                  secondOutcome.set(client.listJobs(null));
                } catch (Exception e) {
                  secondOutcome.set(e);
                } finally {
                  secondFinished.countDown();
                }
              });
      secondLister.start();
      Envelope secondRequest = fake.awaitSent("session.list_jobs");
      fake.deliver(Message.Type.SESSION_JOBS, new SessionJobs(secondRequest.id(), List.of(), null));
      assertThat(secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
      @SuppressWarnings("unchecked")
      Page<JobSummary> lastPage = (Page<JobSummary>) secondOutcome.get();
      assertThat(lastPage.hasNext()).isFalse();
    }
  }

  @Test
  void listJobsSurfacesCorrelatedArcpError() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake)) {
      AtomicReference<Object> outcome = new AtomicReference<>();
      CountDownLatch finished = new CountDownLatch(1);
      Thread lister =
          new Thread(
              () -> {
                try {
                  outcome.set(client.listJobs(null));
                } catch (Exception e) {
                  outcome.set(e);
                } finally {
                  finished.countDown();
                }
              });
      lister.start();
      Envelope request = fake.awaitSent("session.list_jobs");
      fake.deliver(
          Message.Type.JOB_ERROR,
          new JobError(JobError.ERROR, ErrorCode.PERMISSION_DENIED, "denied", false, null),
          null,
          null,
          request.id());
      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(outcome.get()).isInstanceOf(PermissionDeniedException.class);
    }
  }

  @Test
  void listJobsSurfacesInterruption() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake)) {
      AtomicReference<Object> outcome = new AtomicReference<>();
      CountDownLatch finished = new CountDownLatch(1);
      Thread lister =
          new Thread(
              () -> {
                try {
                  outcome.set(client.listJobs(null));
                } catch (Exception e) {
                  outcome.set(e);
                } finally {
                  finished.countDown();
                }
              });
      lister.start();
      fake.awaitSent("session.list_jobs");
      lister.interrupt();
      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
    }
  }

  @Test
  void subscriptionBookkeepingAcrossResubscribeUnsubscribeAndClose() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = connect(fake);
    JobId watched = JobId.of("job_watched");

    Flow.Publisher<EventBody> publisher =
        client.subscribe(watched, SubscribeOptions.withHistory(42L));
    Envelope subscribeEnv = fake.awaitSent("job.subscribe");
    dev.arcp.core.messages.JobSubscribe subscribePayload =
        FakeTransport.MAPPER.convertValue(
            subscribeEnv.payload(), dev.arcp.core.messages.JobSubscribe.class);
    assertThat(subscribePayload.fromEventSeq()).isEqualTo(42L);
    assertThat(subscribePayload.history()).isTrue();

    // Re-subscribing the same job reuses the existing publisher without a new wire message.
    assertThat(client.subscribe(watched, SubscribeOptions.live())).isSameAs(publisher);
    assertThat(fake.sent.poll(100, TimeUnit.MILLISECONDS)).isNull();

    // A live subscription omits from_event_seq.
    JobId liveJob = JobId.of("job_live_only");
    client.subscribe(liveJob, SubscribeOptions.live());
    Envelope liveEnv = fake.awaitSent("job.subscribe");
    dev.arcp.core.messages.JobSubscribe livePayload =
        FakeTransport.MAPPER.convertValue(
            liveEnv.payload(), dev.arcp.core.messages.JobSubscribe.class);
    assertThat(livePayload.fromEventSeq()).isNull();
    assertThat(livePayload.history()).isFalse();

    // Unsubscribe closes the local publisher and notifies the runtime.
    CountDownLatch completed = new CountDownLatch(1);
    publisher.subscribe(completionLatchSubscriber(completed));
    client.unsubscribe(watched);
    assertThat(fake.awaitSent("job.unsubscribe").type()).isEqualTo("job.unsubscribe");
    assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    // A second unsubscribe has no local publisher/executor left but still notifies the runtime.
    client.unsubscribe(watched);
    assertThat(fake.awaitSent("job.unsubscribe")).isNotNull();

    // Failures while notifying the runtime are swallowed.
    JobId flaky = JobId.of("job_flaky");
    client.subscribe(flaky, SubscribeOptions.live());
    fake.awaitSent("job.subscribe");
    fake.failSends = true;
    assertThatCode(() -> client.unsubscribe(flaky)).doesNotThrowAnyException();
    fake.failSends = false;

    // After close() the runtime is no longer notified.
    JobId postClose = JobId.of("job_post_close");
    client.subscribe(postClose, SubscribeOptions.live());
    fake.awaitSent("job.subscribe");
    client.close();
    fake.awaitSent("session.close");
    assertThatCode(() -> client.unsubscribe(postClose)).doesNotThrowAnyException();
    assertThat(fake.sent.poll(100, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void closeIsIdempotentAndSurvivesDeadTransport() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = connect(fake);
    fake.failSends = true; // session.close cannot be sent
    fake.throwOnClose = true; // transport.close() blows up
    assertThatCode(client::close).doesNotThrowAnyException();
    assertThatCode(client::close).doesNotThrowAnyException(); // second close returns immediately
  }

  @Test
  void externallyOwnedSchedulerIsNotShutDownOnClose() throws Exception {
    FakeTransport fake = new FakeTransport();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ArcpClient client =
          connect(
              fake, ArcpClient.builder(fake).scheduler(scheduler).ackInterval(Duration.ofHours(1)));
      client.close();
      assertThat(scheduler.isShutdown()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void builderOptionsFlowIntoHello() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client =
        ArcpClient.builder(fake)
            .mapper(FakeTransport.MAPPER)
            .client("coverage-client", "0.0.1")
            .auth(dev.arcp.core.auth.Auth.anonymous())
            .bearer("hunter2")
            .features(null)
            .features(EnumSet.noneOf(Feature.class))
            .features(EnumSet.of(Feature.ACK))
            .autoAck(true)
            .ackInterval(Duration.ofHours(1))
            .submitTimeout(Duration.ofSeconds(30))
            .build();
    client.connect();
    Envelope hello = fake.awaitSent("session.hello");
    SessionHello payload = FakeTransport.MAPPER.convertValue(hello.payload(), SessionHello.class);
    assertThat(payload.client().name()).isEqualTo("coverage-client");
    assertThat(payload.auth().scheme()).isEqualTo("bearer");
    assertThat(payload.capabilities().features()).containsExactly(Feature.ACK);
    assertThat(payload.resumeToken()).isNull();
    client.close();
  }

  @Test
  void explicitAndAutomaticAcksTrackProgress() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake)) {
      client.ack(3);
      Envelope explicitAck = fake.awaitSent("session.ack");
      assertThat(
              FakeTransport.MAPPER
                  .convertValue(explicitAck.payload(), SessionAck.class)
                  .lastProcessedSeq())
          .isEqualTo(3);

      // Drive maybeAck() directly: progress -> ack, no progress -> silence, send failure -> warn.
      fake.deliver(
          Message.Type.JOB_EVENT,
          new JobEvent(
              "log", Instant.now(), Events.encode(FakeTransport.MAPPER, new LogEvent("info", "x"))),
          JobId.of("job_seq"),
          7L,
          MessageId.generate());
      Method maybeAck = ArcpClient.class.getDeclaredMethod("maybeAck");
      maybeAck.setAccessible(true);
      maybeAck.invoke(client);
      Envelope autoAck = fake.awaitSent("session.ack");
      assertThat(
              FakeTransport.MAPPER
                  .convertValue(autoAck.payload(), SessionAck.class)
                  .lastProcessedSeq())
          .isEqualTo(7);
      maybeAck.invoke(client);
      assertThat(fake.sent.poll(100, TimeUnit.MILLISECONDS)).isNull();

      fake.deliver(
          Message.Type.JOB_EVENT,
          new JobEvent(
              "log", Instant.now(), Events.encode(FakeTransport.MAPPER, new LogEvent("info", "y"))),
          JobId.of("job_seq"),
          9L,
          MessageId.generate());
      fake.failSends = true;
      assertThatCode(() -> maybeAck.invoke(client)).doesNotThrowAnyException();
      fake.failSends = false;
    }
  }

  @Test
  void autoAckSchedulerEmitsAckAfterProgress() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = connect(fake, ArcpClient.builder(fake).ackInterval(Duration.ofMillis(25)));
    fake.deliver(
        Message.Type.JOB_EVENT,
        new JobEvent(
            "log", Instant.now(), Events.encode(FakeTransport.MAPPER, new LogEvent("info", "x"))),
        JobId.of("job_seq"),
        11L,
        MessageId.generate());
    Envelope ack = fake.awaitSent("session.ack");
    assertThat(
            FakeTransport.MAPPER.convertValue(ack.payload(), SessionAck.class).lastProcessedSeq())
        .isEqualTo(11);
    client.close();
  }

  @Test
  void heartbeatWatchdogClosesSessionOnLoss() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = connect(fake);
    CompletableFuture<JobHandle> pending =
        client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
    fake.awaitSent("job.submit");

    Method watch = ArcpClient.class.getDeclaredMethod("watchHeartbeat", long.class);
    watch.setAccessible(true);
    // Recent inbound traffic: nothing happens.
    watch.invoke(client, Long.MAX_VALUE / 4);
    assertThat(pending).isNotDone();
    // Two missed intervals: every in-flight future fails and the client closes.
    watch.invoke(client, -1L);
    assertThat(pending.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(pending::join).hasRootCauseMessage("heartbeat lost");
    // The watchdog already closed the client; another close is a no-op.
    assertThatCode(client::close).doesNotThrowAnyException();
  }

  @Test
  void jobSubmitFactoryValidatesExpiry() {
    assertThatThrownBy(
            () ->
                ArcpClient.jobSubmit(
                    "echo",
                    obj(),
                    null,
                    LeaseConstraints.of(Instant.now().minusSeconds(60)),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expires_at must be in the future");
    assertThatCode(
            () ->
                ArcpClient.jobSubmit(
                    "echo",
                    obj(),
                    null,
                    LeaseConstraints.of(Instant.now().plusSeconds(3600)),
                    "idem-1",
                    30))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> ArcpClient.jobSubmit("echo", obj(), null, LeaseConstraints.none(), null, null))
        .doesNotThrowAnyException();
    assertThatCode(() -> ArcpClient.jobSubmit("echo", obj(), null, null, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void pageDefensiveCopiesAndCursorSemantics() {
    Page<JobSummary> empty = Page.empty();
    assertThat(empty.hasNext()).isFalse();
    assertThat(empty.items()).isEmpty();
    Page<String> nullItems = new Page<>(null, "more");
    assertThat(nullItems.items()).isEmpty();
    assertThat(nullItems.hasNext()).isTrue();
  }

  @Test
  void resultStreamEnforcesResultIdMatch() throws Exception {
    ResultStream bound = ResultStream.toMemory(ResultId.of("r1"));
    assertThatThrownBy(
            () ->
                bound.accept(
                    new ResultChunkEvent(ResultId.of("r2"), 0, "x", ResultChunkEvent.UTF8, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wrong result_id");
    bound.accept(new ResultChunkEvent(ResultId.of("r1"), 0, "ok", ResultChunkEvent.UTF8, false));
    assertThat(bound.isComplete()).isTrue();
    assertThat(new String(bound.bytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("ok");

    ResultStream unbound = ResultStream.toMemory(null);
    unbound.accept(new ResultChunkEvent(ResultId.of("any"), 0, "hi", ResultChunkEvent.UTF8, false));
    assertThat(new String(unbound.bytes(), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("hi");
  }

  private static Flow.Subscriber<EventBody> completionLatchSubscriber(CountDownLatch onComplete) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(EventBody item) {}

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {
        onComplete.countDown();
      }
    };
  }
}
