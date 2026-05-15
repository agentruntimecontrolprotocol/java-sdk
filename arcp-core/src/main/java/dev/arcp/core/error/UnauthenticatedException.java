package dev.arcp.core.error;

public final class UnauthenticatedException extends NonRetryableArcpException {
    public UnauthenticatedException(String message) {
        super(ErrorCode.UNAUTHENTICATED, message);
    }
}
