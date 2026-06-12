package dev.arcp.core.error;

/**
 * Branch of the sealed hierarchy for §12 errors where {@link #retryable()} is always {@code false}:
 * retrying the same operation fails identically.
 */
public abstract non-sealed class NonRetryableArcpException extends ArcpException {
  /**
   * Creates the exception.
   *
   * @param code the §12 error code
   * @param message human-readable detail
   */
  protected NonRetryableArcpException(ErrorCode code, String message) {
    super(code, message);
  }

  @Override
  public final boolean retryable() {
    return false;
  }
}
