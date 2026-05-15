package dev.arcp.core.error;

public final class InvalidRequestException extends NonRetryableArcpException {
    public InvalidRequestException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
