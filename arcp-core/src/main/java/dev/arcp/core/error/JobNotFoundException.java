package dev.arcp.core.error;

public final class JobNotFoundException extends NonRetryableArcpException {
    public JobNotFoundException(String message) {
        super(ErrorCode.JOB_NOT_FOUND, message);
    }
}
