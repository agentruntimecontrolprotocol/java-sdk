package dev.arcp.core.error;

/** §12 {@code PERMISSION_DENIED}: the operation was rejected by lease enforcement (§9.3). */
public final class PermissionDeniedException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public PermissionDeniedException(String message) {
    super(ErrorCode.PERMISSION_DENIED, message);
  }
}
