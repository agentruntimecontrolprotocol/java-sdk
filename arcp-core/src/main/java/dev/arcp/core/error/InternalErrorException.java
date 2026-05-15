package dev.arcp.core.error;

public final class InternalErrorException extends RetryableArcpException {
    public InternalErrorException(String message) {
        super(ErrorCode.INTERNAL_ERROR, message);
    }
}
