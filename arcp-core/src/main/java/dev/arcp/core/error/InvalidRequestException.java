package dev.arcp.core.error;

/** §12 {@code INVALID_REQUEST}: malformed envelope or schema violation. */
public final class InvalidRequestException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public InvalidRequestException(String message) {
    super(ErrorCode.INVALID_REQUEST, message);
  }
}
