package dev.arcp.core.error;

public final class CancelledException extends NonRetryableArcpException {
    public CancelledException(String message) {
        super(ErrorCode.CANCELLED, message);
    }
}
