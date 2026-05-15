package dev.arcp.core.error;

public abstract non-sealed class NonRetryableArcpException extends ArcpException {
    protected NonRetryableArcpException(ErrorCode code, String message) {
        super(code, message);
    }

    @Override
    public final boolean retryable() {
        return false;
    }
}
