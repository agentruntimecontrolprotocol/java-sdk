package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.error.UpstreamBudgetExhaustedException;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.JobSubscribe;
import dev.arcp.core.messages.JobSubscribed;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.SessionPing;
import dev.arcp.core.messages.SessionPong;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.Agent;
import dev.arcp.runtime.agent.JobContext;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.IssuedCredential;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Provisioned-credential branches: issue failures, rotation, surplus revoke, redaction (#98). */
class SessionLoopCredentialTest {

  private static final Set<Feature> WITH_CREDS =
      EnumSet.of(
          Feature.SUBSCRIBE,
          Feature.LIST_JOBS,
          Feature.COST_BUDGET,
          Feature.ACK,
          Feature.PROVISIONED_CREDENTIALS);

  private static IssuedCredential cred(String id, String value, String handle) {
    return new IssuedCredential(
        new Credential(
            CredentialId.of(id),
            CredentialScheme.BEARER,
            value,
            "https://llm.example/v1",
            null,
            null),
        handle);
  }

  private static JobSubmit submit() {
    return new JobSubmit(
        AgentRef.parse("worker@1.0.0"),
        JsonNodeFactory.instance.objectNode(),
        Lease.builder().allow("fs.read", "/workspace/**").build(),
        null,
        null,
        null);
  }

  /** Provisioner whose issue behaviour is scripted per invocation. */
  private static final class ScriptedProvisioner implements CredentialProvisioner {
    volatile Function<JobContext, CompletableFuture<List<IssuedCredential>>> onIssue =
        ctx -> CompletableFuture.completedFuture(List.of());
    final List<CredentialId> revoked = new CopyOnWriteArrayList<>();
    final AtomicInteger issueCalls = new AtomicInteger();

    @Override
    public CompletableFuture<List<IssuedCredential>> issue(
        Lease lease, LeaseConstraints constraints, JobContext ctx) {
      issueCalls.incrementAndGet();
      return onIssue.apply(ctx);
    }

    @Override
    public CompletableFuture<Void> revoke(CredentialId id) {
      revoked.add(id);
      return CompletableFuture.completedFuture(null);
    }
  }

  private static ArcpRuntime runtime(ScriptedProvisioner provisioner, Agent agent) {
    return ArcpRuntime.builder()
        .credentialProvisioner(provisioner)
        .credentialRevocationStore(new InMemoryCredentialRevocationStore())
        .agent("worker", "1.0.0", agent)
        .build();
  }

  @Test
  void issueAttachesCredentialsAndExercisesIssueContext() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    AtomicBoolean cancelledDuringIssue = new AtomicBoolean(true);
    provisioner.onIssue =
        ctx -> {
          ctx.emit(new LogEvent("info", "issuing"));
          cancelledDuringIssue.set(ctx.cancelled());
          try {
            ctx.authorize("fs.read", "/workspace/a.txt");
          } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
          }
          assertThat(ctx.credentials()).isEmpty();
          return CompletableFuture.completedFuture(List.of(cred("cred_1", "secret", "handle-1")));
        };
    try (ArcpRuntime runtime =
        runtime(provisioner, (input, ctx) -> JobOutcome.Success.inline(input.payload()))) {
      assertThat(runtime.advertised()).contains(Feature.PROVISIONED_CREDENTIALS, Feature.MODEL_USE);
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.anonymous(), WITH_CREDS);

      h.send(Message.Type.JOB_SUBMIT, submit());
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(accepted.credentials()).hasSize(1);
      assertThat(accepted.credentials().getFirst().id()).isEqualTo(CredentialId.of("cred_1"));
      assertThat(cancelledDuringIssue).isFalse();
      h.take(Message.Type.JOB_RESULT, JobResult.class);
      // Terminal success revokes the issued credential.
      SessionHarness.await(() -> provisioner.revoked.contains(CredentialId.of("cred_1")));
    }
  }

  @Test
  void issueFailureWithUpstreamBudgetExhaustionMapsToBudgetError() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    provisioner.onIssue =
        ctx ->
            CompletableFuture.failedFuture(
                new UpstreamBudgetExhaustedException("upstream balance empty", "{}"));
    try (ArcpRuntime runtime =
        runtime(provisioner, (input, ctx) -> JobOutcome.Success.inline(input.payload()))) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.anonymous(), WITH_CREDS);
      h.send(Message.Type.JOB_SUBMIT, submit());
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.BUDGET_EXHAUSTED);
      assertThat(err.message()).isEqualTo("upstream balance empty");
      assertThat(runtime.jobs()).isEmpty();
    }
  }

  @Test
  void issueFailureWithMessageMapsToInternalError() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    provisioner.onIssue =
        ctx -> CompletableFuture.failedFuture(new IllegalStateException("issuer offline"));
    try (ArcpRuntime runtime =
        runtime(provisioner, (input, ctx) -> JobOutcome.Success.inline(input.payload()))) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.anonymous(), WITH_CREDS);
      h.send(Message.Type.JOB_SUBMIT, submit());
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
      assertThat(err.message()).isEqualTo("issuer offline");
    }
  }

  @Test
  void issueFailureWithoutMessageUsesClassName() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    provisioner.onIssue =
        ctx -> {
          throw new IllegalStateException();
        };
    try (ArcpRuntime runtime =
        runtime(provisioner, (input, ctx) -> JobOutcome.Success.inline(input.payload()))) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.anonymous(), WITH_CREDS);
      h.send(Message.Type.JOB_SUBMIT, submit());
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
      assertThat(err.message()).isEqualTo("IllegalStateException");
    }
  }

  @Test
  void rotationRevokesSurplusRedactsSubscribersAndReplaysWithoutSecrets() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    provisioner.onIssue =
        ctx -> {
          if (provisioner.issueCalls.get() == 1) {
            return CompletableFuture.completedFuture(List.of(cred("cred_1", "old", "h1")));
          }
          // Reissue during rotation returns a surplus credential too (#98).
          return CompletableFuture.completedFuture(
              List.of(cred("cred_2", "minted", "h2"), cred("cred_3", "surplus", "h3")));
        };
    CountDownLatch subscribed = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        runtime(
            provisioner,
            (input, ctx) -> {
              ctx.emit(new LogEvent("info", "before"));
              subscribed.await();
              ctx.rotateCredential(CredentialId.of("cred_1"), "fresh-value");
              ctx.emit(new LogEvent("info", "after"));
              done.countDown();
              return JobOutcome.Success.inline(input.payload());
            })) {
      SessionHarness owner = SessionHarness.connect(runtime);
      owner.handshake(Auth.bearer("shared"), WITH_CREDS);
      SessionHarness watcher = SessionHarness.connect(runtime);
      watcher.handshake(Auth.bearer("shared"), WITH_CREDS);

      owner.send(Message.Type.JOB_SUBMIT, submit());
      JobAccepted accepted = owner.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      // Take "before" first so the watcher provably subscribes after it was emitted.
      assertThat(owner.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind()).isEqualTo("log");

      watcher.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(accepted.jobId(), null, null));
      watcher.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class);
      subscribed.countDown();
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

      // Owner sees: credential_rotated, after; watcher never sees the rotation event.
      JobEvent rotated = owner.take(Message.Type.JOB_EVENT, JobEvent.class);
      assertThat(rotated.eventKind()).isEqualTo("status");
      assertThat(rotated.body().path("phase").asText()).isEqualTo("credential_rotated");
      assertThat(owner.take(Message.Type.JOB_EVENT, JobEvent.class).eventKind()).isEqualTo("log");
      owner.take(Message.Type.JOB_RESULT, JobResult.class);

      assertThat(
              watcher.take(Message.Type.JOB_EVENT, JobEvent.class).body().path("message").asText())
          .isEqualTo("after");
      watcher.take(Message.Type.JOB_RESULT, JobResult.class);
      watcher.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      watcher.take(Message.Type.SESSION_PONG, SessionPong.class);
      assertThat(watcher.probe().saw(Message.Type.JOB_EVENT)).isFalse();

      // Surplus minted credential and the rotated-out original were both revoked.
      assertThat(provisioner.revoked)
          .contains(CredentialId.of("cred_1"), CredentialId.of("cred_3"));

      // History replay to a non-owner session skips credential_rotated entirely.
      SessionHarness late = SessionHarness.connect(runtime);
      late.handshake(Auth.bearer("shared"), WITH_CREDS);
      late.send(Message.Type.JOB_SUBSCRIBE, new JobSubscribe(accepted.jobId(), 0L, true));
      assertThat(late.take(Message.Type.JOB_SUBSCRIBED, JobSubscribed.class).replayed()).isTrue();
      assertThat(late.take(Message.Type.JOB_EVENT, JobEvent.class).body().path("message").asText())
          .isEqualTo("before");
      assertThat(late.take(Message.Type.JOB_EVENT, JobEvent.class).body().path("message").asText())
          .isEqualTo("after");
      late.send(Message.Type.SESSION_PING, new SessionPing("fence", Instant.now()));
      late.take(Message.Type.SESSION_PONG, SessionPong.class);
      assertThat(late.probe().saw(Message.Type.JOB_EVENT)).isFalse();
    }
  }

  @Test
  void rotateUnknownCredentialIdFailsTheJob() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    provisioner.onIssue =
        ctx -> CompletableFuture.completedFuture(List.of(cred("cred_1", "old", null)));
    try (ArcpRuntime runtime =
        runtime(
            provisioner,
            (input, ctx) -> {
              ctx.rotateCredential(CredentialId.of("cred_unknown"), "v");
              return JobOutcome.Success.inline(input.payload());
            })) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.anonymous(), WITH_CREDS);
      h.send(Message.Type.JOB_SUBMIT, submit());
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
      assertThat(err.message()).contains("unknown credential id");
    }
  }

  @Test
  void rotateWithEmptyReissueFailsLoudly() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    provisioner.onIssue =
        ctx -> {
          if (provisioner.issueCalls.get() == 1) {
            return CompletableFuture.completedFuture(List.of(cred("cred_1", "old", "h1")));
          }
          return CompletableFuture.completedFuture(List.of());
        };
    try (ArcpRuntime runtime =
        runtime(
            provisioner,
            (input, ctx) -> {
              ctx.rotateCredential(CredentialId.of("cred_1"), "v");
              return JobOutcome.Success.inline(input.payload());
            })) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.anonymous(), WITH_CREDS);
      h.send(Message.Type.JOB_SUBMIT, submit());
      h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      JobError err = h.take(Message.Type.JOB_ERROR, JobError.class);
      assertThat(err.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
      assertThat(err.message()).contains("produced no credential");
    }
  }

  @Test
  void credentialsAreNotIssuedWhenFeatureNotNegotiated() throws Exception {
    ScriptedProvisioner provisioner = new ScriptedProvisioner();
    try (ArcpRuntime runtime =
        runtime(provisioner, (input, ctx) -> JobOutcome.Success.inline(input.payload()))) {
      SessionHarness h = SessionHarness.connect(runtime);
      h.handshake(Auth.anonymous(), SessionHarness.DEFAULT_FEATURES);
      h.send(Message.Type.JOB_SUBMIT, submit());
      JobAccepted accepted = h.take(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(accepted.credentials()).isNull();
      assertThat(provisioner.issueCalls.get()).isZero();
      h.take(Message.Type.JOB_RESULT, JobResult.class);
    }
  }
}
