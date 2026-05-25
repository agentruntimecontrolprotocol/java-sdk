package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobSummary;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.wire.ArcpMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class ClientValueTypesTest {

  @Test
  void pageSubscribeOptionsAndSessionDefensivelyCopyInputs() {
    Page<JobSummary> empty = Page.empty();
    assertThat(empty.items()).isEmpty();
    assertThat(empty.hasNext()).isFalse();

    Page<String> page = new Page<>(List.of("a", "b"), "next");
    assertThat(page.items()).containsExactly("a", "b");
    assertThat(page.hasNext()).isTrue();

    assertThat(SubscribeOptions.live()).isEqualTo(new SubscribeOptions(false, 0));
    assertThat(SubscribeOptions.withHistory(4)).isEqualTo(new SubscribeOptions(true, 4));

    EnumSet<Feature> mutableFeatures = EnumSet.of(Feature.SUBSCRIBE);
    List<AgentDescriptor> agents = List.of(new AgentDescriptor("echo", List.of("1.0.0"), "1.0.0"));
    Session session =
        new Session(
            SessionId.of("sess_client"), mutableFeatures, "resume", Duration.ofSeconds(30), agents);
    mutableFeatures.add(Feature.LIST_JOBS);

    assertThat(session.negotiatedFeatures()).containsExactly(Feature.SUBSCRIBE);
    assertThat(session.availableAgents()).isEqualTo(agents);
  }

  @Test
  void jobHandleCredentialDefaultReturnsOptionalCredentials() {
    Credential credential =
        new Credential(
            CredentialId.of("cred_1"),
            CredentialScheme.BEARER,
            "secret",
            "https://api.example.test",
            null,
            null);
    JobHandle withCredentials =
        new FakeJobHandle(
            new JobAccepted(
                JobId.of("job_with_creds"),
                "echo@1.0.0",
                null,
                null,
                null,
                List.of(credential),
                Instant.parse("2026-05-25T12:00:00Z"),
                null));
    assertThat(withCredentials.credentials()).contains(List.of(credential));

    JobHandle withoutCredentials =
        new FakeJobHandle(
            new JobAccepted(
                JobId.of("job_without_creds"),
                "echo@1.0.0",
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-25T12:00:00Z"),
                null));
    assertThat(withoutCredentials.credentials()).isEqualTo(Optional.empty());
  }

  @Test
  void clientBuilderAndJobSubmitHelpersPopulateConfiguration() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try (ArcpClient client =
        ArcpClient.builder(pair.client())
            .mapper(ArcpMapper.shared())
            .client("custom-client", "2.0.0")
            .auth(Auth.anonymous())
            .bearer("token")
            .features(EnumSet.of(Feature.SUBSCRIBE))
            .autoAck(false)
            .ackInterval(Duration.ofSeconds(1))
            .scheduler(scheduler)
            .resumeToken("resume-token")
            .lastEventSeq(7)
            .build()) {
      assertThat(client.lastSeenSeq()).isEqualTo(-1);
      assertThat(
              ArcpClient.jobSubmit("echo@1.0.0", JsonNodeFactory.instance.objectNode())
                  .agent()
                  .wire())
          .isEqualTo("echo@1.0.0");
      assertThat(
              ArcpClient.jobSubmit(
                      "echo@1.0.0",
                      JsonNodeFactory.instance.objectNode(),
                      null,
                      LeaseConstraints.of(Instant.now().plusSeconds(60)),
                      "key",
                      5)
                  .idempotencyKey())
          .isEqualTo("key");
      assertThatThrownBy(
              () ->
                  ArcpClient.jobSubmit(
                      "echo@1.0.0",
                      JsonNodeFactory.instance.objectNode(),
                      null,
                      LeaseConstraints.of(Instant.now().minusSeconds(60)),
                      null,
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expires_at");
    } finally {
      scheduler.shutdownNow();
      pair.runtime().close();
    }
  }

  private record FakeJobHandle(JobAccepted accepted) implements JobHandle {
    @Override
    public JobId jobId() {
      return accepted.jobId();
    }

    @Override
    public String resolvedAgent() {
      return accepted.agent();
    }

    @Override
    public java.util.concurrent.Flow.Publisher<dev.arcp.core.events.EventBody> events() {
      return subscriber -> {};
    }

    @Override
    public java.util.concurrent.CompletableFuture<dev.arcp.core.messages.JobResult> result() {
      return new java.util.concurrent.CompletableFuture<>();
    }

    @Override
    public void cancel() {}
  }
}
