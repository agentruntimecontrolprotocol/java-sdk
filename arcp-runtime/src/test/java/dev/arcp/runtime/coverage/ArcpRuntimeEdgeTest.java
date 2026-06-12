package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.auth.BearerVerifier;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobContext;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.IssuedCredential;
import dev.arcp.runtime.session.SessionLoop;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Builder validation, advertised-feature derivation, and accept/close edges (#33). */
class ArcpRuntimeEdgeTest {

  private static CredentialProvisioner realProvisioner() {
    return new CredentialProvisioner() {
      @Override
      public CompletableFuture<List<IssuedCredential>> issue(
          Lease lease, LeaseConstraints constraints, JobContext ctx) {
        return CompletableFuture.completedFuture(List.of());
      }

      @Override
      public CompletableFuture<Void> revoke(CredentialId id) {
        return CompletableFuture.completedFuture(null);
      }
    };
  }

  @Test
  void explicitComponentsAreExposedThroughGetters() {
    ObjectMapper mapper = ArcpMapper.shared();
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    BearerVerifier verifier = BearerVerifier.staticToken("t", new Principal("p"));
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    try {
      ArcpRuntime runtime =
          ArcpRuntime.builder()
              .mapper(mapper)
              .clock(clock)
              .scheduler(scheduler)
              .workerPool(pool)
              .verifier(verifier)
              .credentialRevocationStore(store)
              .runtimeName("custom-runtime")
              .runtimeVersion("9.9.9")
              .heartbeatIntervalSec(7)
              .resumeWindowSec(11)
              .resumeBufferCapacity(13)
              .idempotencyTtl(Duration.ofMinutes(5))
              .build();
      assertThat(runtime.mapper()).isSameAs(mapper);
      assertThat(runtime.clock()).isSameAs(clock);
      assertThat(runtime.scheduler()).isSameAs(scheduler);
      assertThat(runtime.workerPool()).isSameAs(pool);
      assertThat(runtime.verifier()).isSameAs(verifier);
      assertThat(runtime.credentialRevocationStore()).isSameAs(store);
      assertThat(runtime.runtimeName()).isEqualTo("custom-runtime");
      assertThat(runtime.runtimeVersion()).isEqualTo("9.9.9");
      assertThat(runtime.heartbeatIntervalSec()).isEqualTo(7);
      assertThat(runtime.resumeWindowSec()).isEqualTo(11);
      assertThat(runtime.resumeBufferCapacity()).isEqualTo(13);
      runtime.close();
      // Externally-owned scheduler and worker pool are not shut down by close().
      assertThat(scheduler.isShutdown()).isFalse();
      assertThat(pool.isShutdown()).isFalse();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void provisionerWithDefaultFeaturesAdvertisesCredentialFeatures() {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .credentialProvisioner(realProvisioner())
            .credentialRevocationStore(new InMemoryCredentialRevocationStore())
            .build()) {
      assertThat(runtime.advertised()).contains(Feature.PROVISIONED_CREDENTIALS, Feature.MODEL_USE);
    }
  }

  @Test
  void explicitFeaturesSuppressAutomaticCredentialAdvertisement() {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .credentialProvisioner(realProvisioner())
            .credentialRevocationStore(new InMemoryCredentialRevocationStore())
            .features(EnumSet.of(Feature.SUBSCRIBE))
            .build()) {
      assertThat(runtime.advertised()).containsExactly(Feature.SUBSCRIBE);
    }
  }

  @Test
  void emptyFeatureSetIsAllowed() {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder().features(EnumSet.noneOf(Feature.class)).build()) {
      assertThat(runtime.advertised()).isEmpty();
    }
  }

  @Test
  void nullFeatureSetIsTreatedAsEmpty() {
    try (ArcpRuntime runtime = ArcpRuntime.builder().features(null).build()) {
      assertThat(runtime.advertised()).isEmpty();
    }
  }

  @Test
  void noopProvisionerInstanceDoesNotAdvertiseCredentialFeatures() {
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .credentialProvisioner(dev.arcp.runtime.credentials.NoopCredentialProvisioner.INSTANCE)
            .build()) {
      assertThat(runtime.advertised())
          .doesNotContain(Feature.PROVISIONED_CREDENTIALS, Feature.MODEL_USE);
    }
  }

  @Test
  void modelUseWithoutProvisionerIsRejected() {
    assertThatThrownBy(() -> ArcpRuntime.builder().features(EnumSet.of(Feature.MODEL_USE)).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("model.use");
  }

  @Test
  void acceptOfAlreadyClosedTransportRemovesTheSession() {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      SessionLoop loop = runtime.accept(new CompletedTransport());
      assertThat(loop.phase()).isEqualTo(SessionLoop.Phase.CLOSED);
    }
  }

  @Test
  void closeShutsDownParkedSessions() throws Exception {
    ArcpRuntime runtime = ArcpRuntime.builder().build();
    SessionHarness h = SessionHarness.connect(runtime);
    h.handshake(Auth.bearer("parked"), SessionHarness.DEFAULT_FEATURES);
    h.runtimeEndpoint().close();
    SessionHarness.awaitPhase(h.loop(), SessionLoop.Phase.PARKED);

    runtime.close();
    assertThat(h.loop().phase()).isEqualTo(SessionLoop.Phase.CLOSED);
  }

  /** Transport whose incoming stream completes synchronously on subscribe. */
  private static final class CompletedTransport implements Transport {
    @Override
    public void send(Envelope envelope) {
      throw new IllegalStateException("closed");
    }

    @Override
    public Flow.Publisher<Envelope> incoming() {
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                @Override
                public void request(long n) {
                  subscriber.onComplete();
                }

                @Override
                public void cancel() {}
              });
    }

    @Override
    public void close() {}
  }
}
