package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.lease.LeaseGuard;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Expiry helper and accessor branches for LeaseGuard (#33). */
class LeaseGuardEdgeTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void expiredOrNullCoversAllThreeOutcomes() {
    MutableClock clock = new MutableClock(NOW);
    assertThat(LeaseGuard.expiredOrNull(LeaseConstraints.none(), clock)).isNull();
    assertThat(LeaseGuard.expiredOrNull(LeaseConstraints.of(NOW.plusSeconds(60)), clock)).isNull();
    LeaseExpiredException expired =
        LeaseGuard.expiredOrNull(LeaseConstraints.of(NOW.minusSeconds(1)), clock);
    assertThat(expired).isNotNull();
    assertThat(expired.getMessage()).contains("lease expired");
    // Boundary: exactly-at-expiry counts as expired.
    assertThat(LeaseGuard.expiredOrNull(LeaseConstraints.of(NOW), clock)).isNotNull();
  }

  @Test
  void accessorsAndModelAuthorization() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    Lease lease = Lease.builder().allow("model.use", "gpt-*").build();
    LeaseConstraints constraints = LeaseConstraints.of(NOW.plusSeconds(60));
    LeaseGuard guard = new LeaseGuard(lease, constraints, clock);

    assertThat(guard.lease()).isSameAs(lease);
    assertThat(guard.constraints()).isSameAs(constraints);

    assertThatCode(() -> guard.authorizeModel("gpt-large")).doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.authorizeModel("claude-opus"))
        .isInstanceOf(PermissionDeniedException.class);

    clock.advance(Duration.ofSeconds(120));
    assertThatThrownBy(() -> guard.authorizeModel("gpt-large"))
        .isInstanceOf(LeaseExpiredException.class);
  }
}
