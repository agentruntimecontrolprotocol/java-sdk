package dev.arcp.core.lease;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.error.LeaseSubsetViolationException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LeaseSubsetTest {

  @Test
  void numericBudgetSubsetIsEnforced() {
    Lease parent = Lease.builder().allow("cost.budget", "usd:10.00").build();
    Lease within = Lease.builder().allow("cost.budget", "usd:4.00").build();
    Lease over = Lease.builder().allow("cost.budget", "usd:11.00").build();
    org.assertj.core.api.Assertions.assertThat(parent.contains(within)).isTrue();
    org.assertj.core.api.Assertions.assertThat(parent.contains(over)).isFalse();
  }

  @Test
  void validateAcceptsStrictSubset() {
    Lease parent =
        Lease.builder()
            .allow("fs.read", "/workspace/**")
            .allow("cost.budget", "usd:10")
            .allow("model.use", "tier-fast/*")
            .build();
    Lease child =
        Lease.builder()
            .allow("fs.read", "/workspace/app/**")
            .allow("cost.budget", "usd:3")
            .allow("model.use", "tier-fast/sonnet")
            .build();
    Instant parentExpiry = Instant.parse("2026-06-01T00:00:00Z");
    Instant childExpiry = Instant.parse("2026-05-30T00:00:00Z");
    assertThatCode(() -> LeaseSubset.validate(parent, parentExpiry, child, childExpiry))
        .doesNotThrowAnyException();
  }

  @Test
  void validateRejectsExceedingBudget() {
    Lease parent = Lease.builder().allow("cost.budget", "usd:5").build();
    Lease child = Lease.builder().allow("cost.budget", "usd:6").build();
    assertThatThrownBy(() -> LeaseSubset.validate(parent, null, child, null))
        .isInstanceOf(LeaseSubsetViolationException.class);
  }

  @Test
  void validateRejectsUngrantedCapability() {
    Lease parent = Lease.builder().allow("fs.read", "/workspace/**").build();
    Lease child = Lease.builder().allow("tool.call", "shell").build();
    assertThatThrownBy(() -> LeaseSubset.validate(parent, null, child, null))
        .isInstanceOf(LeaseSubsetViolationException.class);
  }

  @Test
  void validateRejectsExpiryBeyondParent() {
    Lease lease = Lease.builder().allow("fs.read", "/workspace/**").build();
    Instant parentExpiry = Instant.parse("2026-06-01T00:00:00Z");
    Instant childExpiry = Instant.parse("2026-06-02T00:00:00Z");
    assertThatThrownBy(() -> LeaseSubset.validate(lease, parentExpiry, lease, childExpiry))
        .isInstanceOf(LeaseSubsetViolationException.class);
  }
}
