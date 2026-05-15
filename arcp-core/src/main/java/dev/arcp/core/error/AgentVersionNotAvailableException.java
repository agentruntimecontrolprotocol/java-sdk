package dev.arcp.core.error;

public final class AgentVersionNotAvailableException extends NonRetryableArcpException {
    public AgentVersionNotAvailableException(String message) {
        super(ErrorCode.AGENT_VERSION_NOT_AVAILABLE, message);
    }
}
