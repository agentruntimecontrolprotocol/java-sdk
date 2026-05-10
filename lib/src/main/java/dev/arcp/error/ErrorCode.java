package dev.arcp.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical ARCP error codes (RFC §18.2). Each carries its default retryability
 * per RFC §18.3.
 */
public enum ErrorCode {
	OK(false), CANCELLED(false), UNKNOWN(true), INVALID_ARGUMENT(false), DEADLINE_EXCEEDED(true), NOT_FOUND(
			false), ALREADY_EXISTS(false), PERMISSION_DENIED(false), UNAUTHENTICATED(false), RESOURCE_EXHAUSTED(
					true), FAILED_PRECONDITION(false), ABORTED(true), OUT_OF_RANGE(false), UNIMPLEMENTED(
							false), INTERNAL(true), UNAVAILABLE(true), DATA_LOSS(false), LEASE_EXPIRED(
									false), LEASE_REVOKED(false), HEARTBEAT_LOST(true), BACKPRESSURE_OVERFLOW(true);

	private final boolean defaultRetryable;

	ErrorCode(boolean defaultRetryable) {
		this.defaultRetryable = defaultRetryable;
	}

	/** @return default retryability per RFC §18.3. */
	public boolean defaultRetryable() {
		return defaultRetryable;
	}

	/** @return canonical lowercase wire form. */
	@JsonValue
	public String wire() {
		return name();
	}

	/** Jackson factory; accepts the wire form. */
	@JsonCreator
	public static ErrorCode fromWire(String s) {
		return ErrorCode.valueOf(s);
	}
}
