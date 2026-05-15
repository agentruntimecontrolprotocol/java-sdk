package dev.arcp.core.error;

public final class ResumeWindowExpiredException extends NonRetryableArcpException {
    public ResumeWindowExpiredException(String message) {
        super(ErrorCode.RESUME_WINDOW_EXPIRED, message);
    }
}
