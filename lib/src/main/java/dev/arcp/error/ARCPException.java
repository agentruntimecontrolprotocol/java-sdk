package dev.arcp.error;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Base unchecked exception for all ARCP protocol failures (RFC §18). Carries a
 * canonical {@link ErrorCode} and message; subclasses add typed payload.
 */
public class ARCPException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode code;

	public ARCPException(ErrorCode code, String message) {
		super(Objects.requireNonNull(message, "message"));
		this.code = Objects.requireNonNull(code, "code");
	}

	public ARCPException(ErrorCode code, String message, @Nullable Throwable cause) {
		super(Objects.requireNonNull(message, "message"), cause);
		this.code = Objects.requireNonNull(code, "code");
	}

	/** @return canonical error code (RFC §18.2). */
	public ErrorCode code() {
		return code;
	}

	/** @return default retryability per RFC §18.3. */
	public boolean retryable() {
		return code.defaultRetryable();
	}
}
