package dev.arcp.core.error;

/** §12 {@code INTERNAL_ERROR}: unrecoverable runtime fault. Always retryable. */
public final class InternalErrorException extends RetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public InternalErrorException(String message) {
    super(ErrorCode.INTERNAL_ERROR, message);
  }
}
