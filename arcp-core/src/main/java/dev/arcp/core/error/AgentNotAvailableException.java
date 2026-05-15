package dev.arcp.core.error;

public final class AgentNotAvailableException extends NonRetryableArcpException {
    public AgentNotAvailableException(String message) {
        super(ErrorCode.AGENT_NOT_AVAILABLE, message);
    }
}
