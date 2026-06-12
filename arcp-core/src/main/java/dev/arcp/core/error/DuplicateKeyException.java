package dev.arcp.core.error;

/**
 * §12 {@code DUPLICATE_KEY}: an {@code idempotency_key} was reused with conflicting parameters
 * (§7.2).
 */
public final class DuplicateKeyException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public DuplicateKeyException(String message) {
    super(ErrorCode.DUPLICATE_KEY, message);
  }
}
