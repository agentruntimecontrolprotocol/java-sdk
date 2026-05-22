package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.capabilities.Feature;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.IssuedCredential;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ArcpRuntimeBuilderTest {
  @Test
  void defaultRuntimeDoesNotAdvertiseCredentialFeatures() {
    try (ArcpRuntime runtime = ArcpRuntime.builder().build()) {
      assertThat(runtime.advertised())
          .doesNotContain(Feature.PROVISIONED_CREDENTIALS, Feature.MODEL_USE);
    }
  }

  @Test
  void builderRejectsCredentialFeatureWithoutProvisioner() {
    assertThatThrownBy(
            () ->
                ArcpRuntime.builder()
                    .features(EnumSet.of(Feature.PROVISIONED_CREDENTIALS))
                    .credentialRevocationStore(new InMemoryCredentialRevocationStore())
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configured provisioner");
  }

  @Test
  void builderRejectsProvisionerWithoutRevocationStore() {
    assertThatThrownBy(
            () -> ArcpRuntime.builder().credentialProvisioner(noopLikeProvisioner()).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("revocation path");
  }

  private static CredentialProvisioner noopLikeProvisioner() {
    return new CredentialProvisioner() {
      @Override
      public CompletableFuture<List<IssuedCredential>> issue(
          dev.arcp.core.lease.Lease lease,
          dev.arcp.core.lease.LeaseConstraints constraints,
          dev.arcp.runtime.agent.JobContext ctx) {
        return CompletableFuture.completedFuture(List.of());
      }

      @Override
      public CompletableFuture<Void> revoke(dev.arcp.core.credentials.CredentialId id) {
        return CompletableFuture.completedFuture(null);
      }
    };
  }
}
