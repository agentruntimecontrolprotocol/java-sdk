package dev.arcp.core.error;

public final class TimeoutException extends RetryableArcpException {
    public TimeoutException(String message) {
        super(ErrorCode.TIMEOUT, message);
    }
}
