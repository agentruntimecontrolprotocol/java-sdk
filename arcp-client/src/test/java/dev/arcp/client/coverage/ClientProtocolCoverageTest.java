package dev.arcp.client.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.Session;
import dev.arcp.client.SubscribeOptions;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.Events;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobCancel;
import dev.arcp.core.messages.JobCancelled;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.JobSubscribe;
import dev.arcp.core.messages.JobSubscribed;
import dev.arcp.core.messages.JobUnsubscribe;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.RuntimeInfo;
import dev.arcp.core.messages.SessionAck;
import dev.arcp.core.messages.SessionBye;
import dev.arcp.core.messages.SessionClosed;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionListJobs;
import dev.arcp.core.messages.SessionPing;
import dev.arcp.core.messages.SessionPong;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.wire.Envelope;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Branch coverage for {@link ArcpClient} message dispatch and error correlation, driven over a
 * synchronous {@link FakeTransport} so every inbound envelope is fully processed before the test
 * proceeds.
 */
class ClientProtocolCoverageTest {

  private static ObjectNode obj() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static SessionWelcome welcome(
      Set<Feature> features, Integer heartbeatSec, List<AgentDescriptor> agents) {
    return new SessionWelcome(
        new RuntimeInfo("rt", "1.0.0"),
        "resume-1",
        60,
        heartbeatSec,
        new Capabilities(List.of("json"), features, agents));
  }

  private static ArcpClient connect(FakeTransport fake, SessionWelcome welcome) throws Exception {
    ArcpClient client = ArcpClient.builder(fake).ackInterval(Duration.ofHours(1)).build();
    CompletableFuture<Session> future = client.connect();
    fake.awaitSent("session.hello");
    fake.deliver(Message.Type.SESSION_WELCOME, welcome);
    future.get(2, TimeUnit.SECONDS);
    return client;
  }

  private static JobAccepted accepted(JobId jobId, List<Credential> credentials) {
    return new JobAccepted(
        jobId, "echo@1.0.0", Lease.empty(), null, null, credentials, Instant.now(), null);
  }

  private static Credential credential(String id, CredentialScheme scheme) {
    return new Credential(
        CredentialId.of(id), scheme, "secret", "https://api.example.com", null, null);
  }

  private static JobHandle submitAccepted(FakeTransport fake, ArcpClient client, JobId jobId)
      throws Exception {
    CompletableFuture<JobHandle> pending =
        client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
    fake.awaitSent("job.submit");
    fake.deliver(Message.Type.JOB_ACCEPTED, accepted(jobId, null));
    return pending.get(2, TimeUnit.SECONDS);
  }

  @Test
  void welcomeWithoutOptionalFeaturesNegotiatesBareSession() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      Session session = client.session();
      assertThat(session.negotiatedFeatures()).isEmpty();
      assertThat(session.heartbeatInterval()).isNull();
      assertThat(session.availableAgents()).isEmpty();
      assertThat(session.resumeToken()).isEqualTo("resume-1");
      assertThat(client.lastSeenSeq()).isEqualTo(-1);
    }
  }

  @Test
  void welcomeWithHeartbeatAckAndAgentsSchedulesWatchers() throws Exception {
    FakeTransport fake = new FakeTransport();
    SessionWelcome full =
        welcome(
            EnumSet.of(Feature.HEARTBEAT, Feature.ACK),
            1,
            List.of(new AgentDescriptor("echo", List.of("1.0.0"), "1.0.0")));
    try (ArcpClient client = connect(fake, full)) {
      assertThat(client.session().heartbeatInterval()).isEqualTo(Duration.ofSeconds(1));
      assertThat(client.session().availableAgents()).extracting("name").contains("echo");
    }
  }

  @Test
  void welcomeWithIntervalButNoHeartbeatFeatureSkipsWatchdog() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), 1, null))) {
      assertThat(client.session().heartbeatInterval()).isEqualTo(Duration.ofSeconds(1));
      assertThat(client.session().negotiatedFeatures()).doesNotContain(Feature.HEARTBEAT);
    }
  }

  @Test
  void autoAckDisabledSkipsAckScheduling() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = ArcpClient.builder(fake).autoAck(false).build();
    CompletableFuture<Session> future = client.connect();
    fake.awaitSent("session.hello");
    fake.deliver(Message.Type.SESSION_WELCOME, welcome(EnumSet.of(Feature.ACK), null, null));
    assertThat(future.get(2, TimeUnit.SECONDS).negotiatedFeatures()).contains(Feature.ACK);
    client.close();
  }

  @Test
  void sessionAccessorBeforeWelcomeThrows() {
    ArcpClient client = ArcpClient.builder(new FakeTransport()).build();
    assertThatThrownBy(client::session)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not connected");
    client.close();
  }

  @Test
  void pingIsAnsweredWithPongCarryingNonce() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      fake.deliver(Message.Type.SESSION_PING, new SessionPing("nonce-1", Instant.now()));
      Envelope pong = fake.awaitSent("session.pong");
      SessionPong payload = FakeTransport.MAPPER.convertValue(pong.payload(), SessionPong.class);
      assertThat(payload.pingNonce()).isEqualTo("nonce-1");
    }
  }

  @Test
  void clientIgnoresRuntimeBoundAndUnknownMessages() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      JobId jobId = JobId.of("job_ignored");
      fake.deliver(Message.Type.SESSION_CLOSED, new SessionClosed("done"));
      fake.deliver(Message.Type.SESSION_PONG, new SessionPong("n", Instant.now()));
      fake.deliver(Message.Type.SESSION_ACK, new SessionAck(1));
      fake.deliver(
          Message.Type.SESSION_HELLO,
          new SessionHello(new ClientInfo("c", "1"), Auth.anonymous(), null, null, null));
      fake.deliver(Message.Type.SESSION_BYE, new SessionBye("bye"));
      fake.deliver(Message.Type.SESSION_LIST_JOBS, new SessionListJobs(null, null, null));
      fake.deliver(
          Message.Type.JOB_SUBMIT,
          new JobSubmit(AgentRef.parse("echo"), obj(), null, null, null, null));
      fake.deliver(Message.Type.JOB_CANCEL, new JobCancel("nah"));
      fake.deliver(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(jobId, null, false));
      fake.deliver(
          Message.Type.JOB_SUBSCRIBED,
          new JobSubscribed(jobId, "running", "echo", null, null, null, 0, false));
      fake.deliver(Message.Type.JOB_UNSUBSCRIBE, new JobUnsubscribe(jobId));
      fake.deliver(
          FakeTransport.envelope(
              Message.Type.JOB_CANCELLED,
              new JobCancelled("user asked"),
              jobId,
              null,
              MessageId.generate()));

      // Legacy `session.bye` alias decodes through the same dispatch arm.
      fake.deliver(
          new Envelope(
              Envelope.VERSION,
              MessageId.generate(),
              "session.bye",
              FakeTransport.SESSION,
              null,
              null,
              null,
              FakeTransport.MAPPER.valueToTree(new SessionBye("legacy"))));
      // Unknown type and undecodable payload both log-and-drop.
      fake.deliver(
          new Envelope(
              Envelope.VERSION,
              MessageId.generate(),
              "totally.unknown",
              null,
              null,
              null,
              null,
              obj()));
      fake.deliver(
          new Envelope(
              Envelope.VERSION, MessageId.generate(), "job.event", null, null, null, null, obj()));

      // The client survives all of the above.
      fake.deliver(Message.Type.SESSION_PING, new SessionPing("still-alive", Instant.now()));
      assertThat(fake.awaitSent("session.pong")).isNotNull();
    }
  }

  @Test
  void dispatchSwallowsHandlerExceptions() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      fake.failSends = true;
      fake.deliver(Message.Type.SESSION_PING, new SessionPing("boom", Instant.now()));
      fake.failSends = false;
      fake.deliver(Message.Type.SESSION_PING, new SessionPing("ok", Instant.now()));
      assertThat(fake.awaitSent("session.pong")).isNotNull();
    }
  }

  @Test
  void lastSeenSeqTracksMonotonicMaximum() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      JobEvent event =
          new JobEvent(
              "log", Instant.now(), Events.encode(FakeTransport.MAPPER, new LogEvent("info", "x")));
      fake.deliver(
          Message.Type.JOB_EVENT, event, JobId.of("job_unknown"), 5L, MessageId.generate());
      fake.deliver(
          Message.Type.JOB_EVENT, event, JobId.of("job_unknown"), 3L, MessageId.generate());
      assertThat(client.lastSeenSeq()).isEqualTo(5);

      // Events and results without a job id are dropped before dispatching to subscribers.
      fake.deliver(Message.Type.JOB_EVENT, event, null, null, MessageId.generate());
      fake.deliver(
          Message.Type.JOB_RESULT,
          new JobResult(JobResult.SUCCESS, null, null, obj(), null),
          null,
          null,
          MessageId.generate());
      // A result for an unknown job is ignored too.
      fake.deliver(
          Message.Type.JOB_RESULT,
          new JobResult(JobResult.SUCCESS, null, null, obj(), null),
          JobId.of("job_unknown"),
          null,
          MessageId.generate());
      assertThat(client.lastSeenSeq()).isEqualTo(5);
    }
  }

  @Test
  void credentialFilteringDropsUnknownSchemes() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      // Mixed list: the unknown scheme is dropped, the bearer survives.
      CompletableFuture<JobHandle> mixed =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      fake.awaitSent("job.submit");
      fake.deliver(
          Message.Type.JOB_ACCEPTED,
          accepted(
              JobId.of("job_mixed"),
              List.of(
                  credential("c1", CredentialScheme.BEARER),
                  credential("c2", CredentialScheme.UNKNOWN))));
      JobHandle mixedHandle = mixed.get(2, TimeUnit.SECONDS);
      assertThat(mixedHandle.credentials()).isPresent();
      assertThat(mixedHandle.credentials().orElseThrow())
          .extracting(Credential::scheme)
          .containsExactly(CredentialScheme.BEARER);

      // All unknown: the credential list collapses to absent.
      CompletableFuture<JobHandle> allUnknown =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      fake.awaitSent("job.submit");
      fake.deliver(
          Message.Type.JOB_ACCEPTED,
          accepted(JobId.of("job_unknowns"), List.of(credential("c3", CredentialScheme.UNKNOWN))));
      assertThat(allUnknown.get(2, TimeUnit.SECONDS).credentials()).isEmpty();

      // All recognized: fast path returns the acceptance untouched.
      CompletableFuture<JobHandle> recognized =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      fake.awaitSent("job.submit");
      fake.deliver(
          Message.Type.JOB_ACCEPTED,
          accepted(JobId.of("job_known"), List.of(credential("c4", CredentialScheme.BEARER))));
      assertThat(recognized.get(2, TimeUnit.SECONDS).credentials().orElseThrow()).hasSize(1);

      // Empty and absent credential lists short-circuit the filter.
      CompletableFuture<JobHandle> empty =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      fake.awaitSent("job.submit");
      fake.deliver(Message.Type.JOB_ACCEPTED, accepted(JobId.of("job_empty"), List.of()));
      assertThat(empty.get(2, TimeUnit.SECONDS).credentials().orElseThrow()).isEmpty();

      CompletableFuture<JobHandle> absent =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      fake.awaitSent("job.submit");
      fake.deliver(Message.Type.JOB_ACCEPTED, accepted(JobId.of("job_absent"), null));
      JobHandle absentHandle = absent.get(2, TimeUnit.SECONDS);
      assertThat(absentHandle.credentials()).isEmpty();
      assertThat(absentHandle.resolvedAgent()).isEqualTo("echo@1.0.0");
      assertThat(absentHandle.jobId()).isEqualTo(JobId.of("job_absent"));
      assertThat(absentHandle.accepted().agent()).isEqualTo("echo@1.0.0");

      // job.accepted with no pending submit is dropped.
      fake.deliver(Message.Type.JOB_ACCEPTED, accepted(JobId.of("job_spurious"), null));
      assertThat(fake.sent.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }
  }

  @Test
  void cancelSendsJobCancelForAcceptedJob() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      JobHandle handle = submitAccepted(fake, client, JobId.of("job_cancel"));
      handle.cancel();
      Envelope cancel = fake.awaitSent("job.cancel");
      assertThat(cancel.jobId()).isEqualTo(JobId.of("job_cancel"));
    }
  }

  @Test
  void jobfulErrorFailsResultAndUnknownJobErrorIsIgnored() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      JobHandle handle = submitAccepted(fake, client, JobId.of("job_err"));
      // A live subscriber on the failing job must observe the terminal error too.
      CountDownLatch liveErrored = new CountDownLatch(1);
      client
          .subscribe(JobId.of("job_err"), SubscribeOptions.live())
          .subscribe(errorLatchSubscriber(liveErrored));
      fake.awaitSent("job.subscribe");
      // An error for some other job leaves this handle untouched.
      fake.deliver(
          Message.Type.JOB_ERROR,
          new JobError(JobError.ERROR, ErrorCode.INTERNAL_ERROR, "other", true, null),
          JobId.of("job_other"),
          null,
          MessageId.generate());
      assertThat(handle.result()).isNotDone();
      // The terminal error for the known job fails the result future.
      fake.deliver(
          Message.Type.JOB_ERROR,
          new JobError(JobError.ERROR, ErrorCode.BUDGET_EXHAUSTED, "broke", false, null),
          JobId.of("job_err"),
          null,
          MessageId.generate());
      assertThatThrownBy(() -> handle.result().get(2, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(BudgetExhaustedException.class);
      assertThat(liveErrored.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void topLevelErrorCorrelatesToPendingSubmitById() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      CompletableFuture<JobHandle> first =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      Envelope firstSubmit = fake.awaitSent("job.submit");
      CompletableFuture<JobHandle> second =
          client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
      Envelope secondSubmit = fake.awaitSent("job.submit");

      // Failing the SECOND request exercises the non-head scan of pending submits.
      fake.deliver(
          Message.Type.JOB_ERROR,
          new JobError(JobError.ERROR, ErrorCode.BUDGET_EXHAUSTED, "no funds", false, null),
          null,
          null,
          secondSubmit.id());
      assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(BudgetExhaustedException.class);
      assertThat(first).isNotDone();

      // The first submit still completes when its acceptance arrives.
      fake.deliver(Message.Type.JOB_ACCEPTED, accepted(JobId.of("job_first"), null));
      assertThat(first.get(2, TimeUnit.SECONDS).jobId()).isEqualTo(JobId.of("job_first"));
    }
  }

  @Test
  void uncorrelatedTopLevelErrorAfterWelcomeIsDropped() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      fake.deliver(
          Message.Type.JOB_ERROR,
          new JobError(JobError.ERROR, ErrorCode.INTERNAL_ERROR, "stray", true, null),
          null,
          null,
          MessageId.generate());
      fake.deliver(Message.Type.SESSION_PING, new SessionPing("post-stray", Instant.now()));
      assertThat(fake.awaitSent("session.pong")).isNotNull();
    }
  }

  @Test
  void topLevelErrorBeforeWelcomeRejectsHandshake() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = ArcpClient.builder(fake).resumeToken("expired").lastEventSeq(17).build();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread connector =
        new Thread(
            () -> {
              try {
                client.connect(Duration.ofSeconds(5));
              } catch (Exception e) {
                thrown.set(e);
              }
            });
    connector.start();
    Envelope hello = fake.awaitSent("session.hello");
    SessionHello payload = FakeTransport.MAPPER.convertValue(hello.payload(), SessionHello.class);
    assertThat(payload.resumeToken()).isEqualTo("expired");
    assertThat(payload.lastEventSeq()).isEqualTo(17);
    fake.deliver(
        Message.Type.JOB_ERROR,
        new JobError(JobError.ERROR, ErrorCode.RESUME_WINDOW_EXPIRED, "expired", false, null),
        null,
        null,
        MessageId.generate());
    connector.join(TimeUnit.SECONDS.toMillis(5));
    assertThat(thrown.get())
        .isInstanceOf(dev.arcp.core.error.ResumeWindowExpiredException.class)
        .hasMessageContaining("expired");
    client.close();
  }

  @Test
  void transportCompletionBeforeWelcomeFailsConnect() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = ArcpClient.builder(fake).build();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread connector =
        new Thread(
            () -> {
              try {
                client.connect(Duration.ofSeconds(5));
              } catch (Exception e) {
                thrown.set(e);
              }
            });
    connector.start();
    fake.awaitSent("session.hello");
    fake.completeInbound();
    connector.join(TimeUnit.SECONDS.toMillis(5));
    assertThat(thrown.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connect failed");
    assertThat(thrown.get().getCause()).hasMessageContaining("transport closed before welcome");
    client.close();
  }

  @Test
  void transportCompletionAfterWelcomeFailsEverythingInFlight() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = connect(fake, welcome(Set.of(), null, null));

    JobHandle live = submitAccepted(fake, client, JobId.of("job_live"));
    JobHandle done = submitAccepted(fake, client, JobId.of("job_done"));
    done.result().complete(new JobResult(JobResult.SUCCESS, null, null, obj(), null));

    CompletableFuture<JobHandle> pendingSubmit =
        client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
    fake.awaitSent("job.submit");

    AtomicReference<Object> listOutcome = new AtomicReference<>();
    CountDownLatch listDone = new CountDownLatch(1);
    Thread lister =
        new Thread(
            () -> {
              try {
                listOutcome.set(client.listJobs(null));
              } catch (Exception e) {
                listOutcome.set(e);
              } finally {
                listDone.countDown();
              }
            });
    lister.start();
    fake.awaitSent("session.list_jobs");

    CountDownLatch subscriberError = new CountDownLatch(1);
    client
        .subscribe(JobId.of("job_watched"), SubscribeOptions.live())
        .subscribe(errorLatchSubscriber(subscriberError));
    fake.awaitSent("job.subscribe");

    fake.completeInbound();

    assertThat(pendingSubmit.isCompletedExceptionally()).isTrue();
    assertThat(live.result().isCompletedExceptionally()).isTrue();
    assertThat(done.result().get()).isNotNull();
    assertThat(listDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(listOutcome.get())
        .isInstanceOf(IllegalStateException.class)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.THROWABLE)
        .hasMessageContaining("list_jobs failed");
    assertThat(subscriberError.await(5, TimeUnit.SECONDS)).isTrue();
    client.close();
  }

  @Test
  void transportErrorFailsPendingSubmit() throws Exception {
    FakeTransport fake = new FakeTransport();
    ArcpClient client = connect(fake, welcome(Set.of(), null, null));
    CompletableFuture<JobHandle> pending =
        client.submitAsync(ArcpClient.jobSubmit("echo@1.0.0", obj()));
    fake.awaitSent("job.submit");
    fake.errorInbound(new RuntimeException("link down"));
    assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS)).hasRootCauseMessage("link down");
    client.close();
  }

  private static Flow.Subscriber<EventBody> errorLatchSubscriber(CountDownLatch onError) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(EventBody item) {}

      @Override
      public void onError(Throwable throwable) {
        onError.countDown();
      }

      @Override
      public void onComplete() {}
    };
  }

  @Test
  void eventsAreFannedOutToHandleAndLiveSubscribers() throws Exception {
    FakeTransport fake = new FakeTransport();
    try (ArcpClient client = connect(fake, welcome(Set.of(), null, null))) {
      JobId jobId = JobId.of("job_fan");
      JobHandle handle = submitAccepted(fake, client, jobId);

      CopyOnWriteArrayList<String> handleLogs = new CopyOnWriteArrayList<>();
      CountDownLatch handleSaw = new CountDownLatch(1);
      handle.events().subscribe(collectingSubscriber(handleLogs, handleSaw));

      CopyOnWriteArrayList<String> liveLogs = new CopyOnWriteArrayList<>();
      CountDownLatch liveSaw = new CountDownLatch(1);
      client
          .subscribe(jobId, SubscribeOptions.withHistory(0L))
          .subscribe(collectingSubscriber(liveLogs, liveSaw));
      Envelope sub = fake.awaitSent("job.subscribe");
      JobSubscribe subscribePayload =
          FakeTransport.MAPPER.convertValue(sub.payload(), JobSubscribe.class);
      assertThat(subscribePayload.fromEventSeq()).isEqualTo(0L);

      fake.deliver(
          Message.Type.JOB_EVENT,
          new JobEvent(
              "log",
              Instant.now(),
              Events.encode(FakeTransport.MAPPER, new LogEvent("info", "hi"))),
          jobId,
          1L,
          MessageId.generate());
      assertThat(handleSaw.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(liveSaw.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(handleLogs).containsExactly("hi");
      assertThat(liveLogs).containsExactly("hi");

      fake.deliver(
          Message.Type.JOB_RESULT,
          new JobResult(JobResult.SUCCESS, null, null, obj(), null),
          jobId,
          2L,
          MessageId.generate());
      assertThat(handle.result().get(2, TimeUnit.SECONDS).finalStatus())
          .isEqualTo(JobResult.SUCCESS);
    }
  }

  private static Flow.Subscriber<EventBody> collectingSubscriber(
      CopyOnWriteArrayList<String> sink, CountDownLatch first) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(EventBody item) {
        if (item instanceof LogEvent log) {
          sink.add(log.message());
        }
        first.countDown();
      }

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}
    };
  }
}
