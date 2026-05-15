package dev.arcp.core.error;

public final class PermissionDeniedException extends NonRetryableArcpException {
    public PermissionDeniedException(String message) {
        super(ErrorCode.PERMISSION_DENIED, message);
    }
}
