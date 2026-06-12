package dev.arcp.core.error;

/**
 * §12 {@code RESUME_WINDOW_EXPIRED}: resume was attempted after the event buffer window closed
 * (§6.3).
 */
public final class ResumeWindowExpiredException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public ResumeWindowExpiredException(String message) {
    super(ErrorCode.RESUME_WINDOW_EXPIRED, message);
  }
}
