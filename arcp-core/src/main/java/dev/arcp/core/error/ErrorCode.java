package dev.arcp.core.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ErrorCode {
    PERMISSION_DENIED(false),
    LEASE_SUBSET_VIOLATION(false),
    JOB_NOT_FOUND(false),
    DUPLICATE_KEY(false),
    AGENT_NOT_AVAILABLE(false),
    AGENT_VERSION_NOT_AVAILABLE(false),
    CANCELLED(false),
    TIMEOUT(true),
    RESUME_WINDOW_EXPIRED(false),
    HEARTBEAT_LOST(true),
    LEASE_EXPIRED(false),
    BUDGET_EXHAUSTED(false),
    INVALID_REQUEST(false),
    UNAUTHENTICATED(false),
    INTERNAL_ERROR(true);

    private final boolean retryable;

    ErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    @JsonValue
    public String wire() {
        return name();
    }

    @JsonCreator
    public static ErrorCode fromWire(String wire) {
        for (ErrorCode c : values()) {
            if (c.name().equals(wire)) {
                return c;
            }
        }
        return INTERNAL_ERROR;
    }
}
