package dev.arcp.core.error;

/** §12 {@code TIMEOUT}: the job exceeded {@code max_runtime_sec}. Retryable. */
public final class TimeoutException extends RetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public TimeoutException(String message) {
    super(ErrorCode.TIMEOUT, message);
  }
}
