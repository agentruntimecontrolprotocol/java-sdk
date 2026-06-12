package dev.arcp.core.error;

/**
 * §12 {@code LEASE_SUBSET_VIOLATION}: a delegation request expanded beyond the parent lease (§9.4).
 */
public final class LeaseSubsetViolationException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public LeaseSubsetViolationException(String message) {
    super(ErrorCode.LEASE_SUBSET_VIOLATION, message);
  }
}
