package dev.arcp.core.error;

public final class HeartbeatLostException extends RetryableArcpException {
    public HeartbeatLostException(String message) {
        super(ErrorCode.HEARTBEAT_LOST, message);
    }
}
