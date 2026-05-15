package dev.arcp.core.error;

public abstract non-sealed class RetryableArcpException extends ArcpException {
    protected RetryableArcpException(ErrorCode code, String message) {
        super(code, message);
    }

    @Override
    public final boolean retryable() {
        return true;
    }
}
