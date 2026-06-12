package dev.arcp.core.error;

/**
 * §12 {@code LEASE_EXPIRED}: the lease's {@code expires_at} was reached during execution (§9.5).
 * Never retryable — a naive retry fails identically.
 */
public final class LeaseExpiredException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public LeaseExpiredException(String message) {
    super(ErrorCode.LEASE_EXPIRED, message);
  }
}
