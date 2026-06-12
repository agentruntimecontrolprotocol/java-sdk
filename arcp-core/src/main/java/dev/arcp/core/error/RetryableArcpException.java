package dev.arcp.core.error;

/**
 * Branch of the sealed hierarchy for §12 errors where {@link #retryable()} is always {@code true}:
 * retrying the operation may succeed.
 */
public abstract non-sealed class RetryableArcpException extends ArcpException {
  /**
   * Creates the exception.
   *
   * @param code the §12 error code
   * @param message human-readable detail
   */
  protected RetryableArcpException(ErrorCode code, String message) {
    super(code, message);
  }

  @Override
  public final boolean retryable() {
    return true;
  }
}
