package dev.arcp.core.lease;

import dev.arcp.core.error.LeaseSubsetViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * §9.4 / §10 delegation subset enforcement. A delegated (child) lease MUST be a strict subset of its
 * parent: every capability pattern covered by the parent, every {@code cost.budget} amount no
 * greater than the parent's remaining amount, every {@code model.use} pattern covered, and an {@code
 * expires_at} no later than the parent's. Violations are rejected with {@code
 * LEASE_SUBSET_VIOLATION}.
 *
 * <p>The numeric/temporal comparisons here intentionally do not reuse the glob {@code covers()}
 * logic, which cannot bound a budget amount or an expiry instant.
 */
public final class LeaseSubset {

  private LeaseSubset() {}

  /**
   * Validate that {@code child} is a strict subset of {@code parent}.
   *
   * @param parent the parent (delegating) lease
   * @param parentExpiresAt the parent lease expiry, or {@code null} if unbounded
   * @param child the requested child (delegated) lease
   * @param childExpiresAt the requested child expiry, or {@code null} if unbounded
   * @throws LeaseSubsetViolationException if the child exceeds the parent in any dimension
   */
  public static void validate(
      Lease parent,
      @Nullable Instant parentExpiresAt,
      Lease child,
      @Nullable Instant childExpiresAt)
      throws LeaseSubsetViolationException {
    for (Map.Entry<String, List<String>> entry : child.capabilities().entrySet()) {
      String namespace = entry.getKey();
      if ("cost.budget".equals(namespace)) {
        validateBudget(parent.budget(), child.budget());
        continue;
      }
      List<String> parentPatterns = parent.patterns(namespace);
      if (parentPatterns.isEmpty()) {
        throw new LeaseSubsetViolationException(
            "delegated capability namespace not granted by parent: " + namespace);
      }
      // Reuse Lease.contains for single-namespace pattern coverage.
      Lease childSlice = new Lease(Map.of(namespace, entry.getValue()));
      if (!parent.contains(childSlice)) {
        throw new LeaseSubsetViolationException(
            "delegated patterns exceed parent for namespace " + namespace + ": " + entry.getValue());
      }
    }
    validateExpiry(parentExpiresAt, childExpiresAt);
  }

  private static void validateBudget(
      Map<String, BigDecimal> parentBudget, Map<String, BigDecimal> childBudget)
      throws LeaseSubsetViolationException {
    for (Map.Entry<String, BigDecimal> entry : childBudget.entrySet()) {
      BigDecimal parentAmount = parentBudget.get(entry.getKey());
      if (parentAmount == null) {
        throw new LeaseSubsetViolationException(
            "delegated budget currency not granted by parent: " + entry.getKey());
      }
      if (entry.getValue().compareTo(parentAmount) > 0) {
        throw new LeaseSubsetViolationException(
            "delegated budget "
                + entry.getKey()
                + ":"
                + entry.getValue()
                + " exceeds parent "
                + parentAmount);
      }
    }
  }

  private static void validateExpiry(
      @Nullable Instant parentExpiresAt, @Nullable Instant childExpiresAt)
      throws LeaseSubsetViolationException {
    if (parentExpiresAt == null) {
      return; // parent unbounded → any child expiry is a subset
    }
    if (childExpiresAt == null || childExpiresAt.isAfter(parentExpiresAt)) {
      throw new LeaseSubsetViolationException(
          "delegated expires_at " + childExpiresAt + " exceeds parent " + parentExpiresAt);
    }
  }
}
