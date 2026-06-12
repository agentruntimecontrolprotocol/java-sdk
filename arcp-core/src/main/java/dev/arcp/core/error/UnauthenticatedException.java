package dev.arcp.core.error;

/** §12 {@code UNAUTHENTICATED}: missing or invalid authentication (§6.1). */
public final class UnauthenticatedException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public UnauthenticatedException(String message) {
    super(ErrorCode.UNAUTHENTICATED, message);
  }
}
