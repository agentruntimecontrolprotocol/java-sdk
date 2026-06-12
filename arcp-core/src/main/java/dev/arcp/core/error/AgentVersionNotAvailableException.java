package dev.arcp.core.error;

/**
 * §12 {@code AGENT_VERSION_NOT_AVAILABLE}: the agent name resolved but the requested version is
 * unavailable (§7.5).
 */
public final class AgentVersionNotAvailableException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public AgentVersionNotAvailableException(String message) {
    super(ErrorCode.AGENT_VERSION_NOT_AVAILABLE, message);
  }
}
