package dev.arcp.core.error;

/**
 * §12 {@code JOB_NOT_FOUND}: the referenced {@code job_id} does not exist or is not visible to the
 * session's principal.
 */
public final class JobNotFoundException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public JobNotFoundException(String message) {
    super(ErrorCode.JOB_NOT_FOUND, message);
  }
}
