package dev.arcp.core.coverage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.error.LeaseSubsetViolationException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseSubset;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Branch coverage for §9.4 delegation subset validation. */
class LeaseSubsetCoverageTest {

  private static final Instant PARENT_EXPIRY = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void acceptsCoveredPatternsWithUnboundedParentExpiry() {
    Lease parent = Lease.builder().allow("fs.read", "/a/**").build();
    Lease child = Lease.builder().allow("fs.read", "/a/b").build();
    assertThatCode(() -> LeaseSubset.validate(parent, null, child, null))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsNamespaceNotGrantedByParent() {
    Lease parent = Lease.builder().allow("fs.read", "/a/**").build();
    Lease child = Lease.builder().allow("net.fetch", "example.com").build();
    assertThatThrownBy(() -> LeaseSubset.validate(parent, null, child, null))
        .isInstanceOf(LeaseSubsetViolationException.class)
        .hasMessageContaining("namespace not granted");
  }

  @Test
  void rejectsPatternsExceedingParent() {
    Lease parent = Lease.builder().allow("fs.read", "/a/*").build();
    Lease child = Lease.builder().allow("fs.read", "/a/b/c").build();
    assertThatThrownBy(() -> LeaseSubset.validate(parent, null, child, null))
        .isInstanceOf(LeaseSubsetViolationException.class)
        .hasMessageContaining("exceed parent");
  }

  @Test
  void acceptsBudgetWithinParentAllowance() {
    Lease parent = Lease.builder().allow("cost.budget", "usd:10").build();
    Lease child = Lease.builder().allow("cost.budget", "usd:10").build();
    assertThatCode(() -> LeaseSubset.validate(parent, null, child, null))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsBudgetCurrencyNotGrantedByParent() {
    Lease parent = Lease.builder().allow("cost.budget", "usd:10").build();
    Lease child = Lease.builder().allow("cost.budget", "eur:1").build();
    assertThatThrownBy(() -> LeaseSubset.validate(parent, null, child, null))
        .isInstanceOf(LeaseSubsetViolationException.class)
        .hasMessageContaining("currency not granted");
  }

  @Test
  void rejectsBudgetExceedingParentAmount() {
    Lease parent = Lease.builder().allow("cost.budget", "usd:10").build();
    Lease child = Lease.builder().allow("cost.budget", "usd:10.50").build();
    assertThatThrownBy(() -> LeaseSubset.validate(parent, null, child, null))
        .isInstanceOf(LeaseSubsetViolationException.class)
        .hasMessageContaining("exceeds parent");
  }

  @Test
  void rejectsUnboundedChildExpiryWhenParentIsBounded() {
    Lease lease = Lease.builder().allow("fs.read", "/a").build();
    assertThatThrownBy(() -> LeaseSubset.validate(lease, PARENT_EXPIRY, lease, null))
        .isInstanceOf(LeaseSubsetViolationException.class)
        .hasMessageContaining("expires_at");
  }

  @Test
  void rejectsChildExpiryAfterParentExpiry() {
    Lease lease = Lease.builder().allow("fs.read", "/a").build();
    Instant later = PARENT_EXPIRY.plusSeconds(1);
    assertThatThrownBy(() -> LeaseSubset.validate(lease, PARENT_EXPIRY, lease, later))
        .isInstanceOf(LeaseSubsetViolationException.class)
        .hasMessageContaining("exceeds parent");
  }

  @Test
  void acceptsChildExpiryAtOrBeforeParentExpiry() {
    Lease lease = Lease.builder().allow("fs.read", "/a").build();
    assertThatCode(() -> LeaseSubset.validate(lease, PARENT_EXPIRY, lease, PARENT_EXPIRY))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> LeaseSubset.validate(lease, PARENT_EXPIRY, lease, PARENT_EXPIRY.minusSeconds(60)))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsAnyChildExpiryWhenParentIsUnbounded() {
    Lease lease = Lease.builder().allow("fs.read", "/a").build();
    assertThatCode(() -> LeaseSubset.validate(lease, null, lease, PARENT_EXPIRY))
        .doesNotThrowAnyException();
  }
}
