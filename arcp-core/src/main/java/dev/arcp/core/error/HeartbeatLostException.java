package dev.arcp.core.error;

/**
 * §12 {@code HEARTBEAT_LOST}: no traffic from the peer for two consecutive heartbeat intervals, so
 * the connection is presumed dead (§6.4). Retryable — the session remains resumable within the
 * resume window.
 */
public final class HeartbeatLostException extends RetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public HeartbeatLostException(String message) {
    super(ErrorCode.HEARTBEAT_LOST, message);
  }
}
