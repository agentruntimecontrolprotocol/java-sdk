package dev.arcp.core.error;

/** §12 {@code CANCELLED}: the job ended due to client cancellation (§7.4). */
public final class CancelledException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public CancelledException(String message) {
    super(ErrorCode.CANCELLED, message);
  }
}
