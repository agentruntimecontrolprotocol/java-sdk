package dev.arcp.core.error;

public final class LeaseSubsetViolationException extends NonRetryableArcpException {
    public LeaseSubsetViolationException(String message) {
        super(ErrorCode.LEASE_SUBSET_VIOLATION, message);
    }
}
