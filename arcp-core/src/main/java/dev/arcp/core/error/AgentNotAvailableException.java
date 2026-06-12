package dev.arcp.core.error;

/** §12 {@code AGENT_NOT_AVAILABLE}: the requested {@code agent} is not registered. */
public final class AgentNotAvailableException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public AgentNotAvailableException(String message) {
    super(ErrorCode.AGENT_NOT_AVAILABLE, message);
  }
}
