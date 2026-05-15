package dev.arcp.core.error;

public final class LeaseExpiredException extends NonRetryableArcpException {
    public LeaseExpiredException(String message) {
        super(ErrorCode.LEASE_EXPIRED, message);
    }
}
