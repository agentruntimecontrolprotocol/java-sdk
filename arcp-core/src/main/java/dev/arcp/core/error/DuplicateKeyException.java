package dev.arcp.core.error;

public final class DuplicateKeyException extends NonRetryableArcpException {
    public DuplicateKeyException(String message) {
        super(ErrorCode.DUPLICATE_KEY, message);
    }
}
