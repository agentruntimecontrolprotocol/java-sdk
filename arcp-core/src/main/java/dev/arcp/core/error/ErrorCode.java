package dev.arcp.core.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Canonical §12 error codes, each carrying its default retryability. */
public enum ErrorCode {
  /** Operation rejected by lease enforcement (§9.3). */
  PERMISSION_DENIED(false),

  /** Delegation request expanded beyond the parent lease (§9.4). */
  LEASE_SUBSET_VIOLATION(false),

  /** Referenced {@code job_id} does not exist or is not visible to the principal. */
  JOB_NOT_FOUND(false),

  /** {@code idempotency_key} reused with conflicting parameters (§7.2). */
  DUPLICATE_KEY(false),

  /** Requested {@code agent} is not registered. */
  AGENT_NOT_AVAILABLE(false),

  /** Agent name resolved but the requested version is unavailable (§7.5). */
  AGENT_VERSION_NOT_AVAILABLE(false),

  /** Job ended due to client cancellation (§7.4). */
  CANCELLED(false),

  /** Job exceeded {@code max_runtime_sec}; retryable. */
  TIMEOUT(true),

  /** Resume attempted after the buffer window closed (§6.3). */
  RESUME_WINDOW_EXPIRED(false),

  /** Peer detected counterparty disconnection (§6.4); retryable via resume. */
  HEARTBEAT_LOST(true),

  /** Lease's {@code expires_at} reached during execution (§9.5); never retryable. */
  LEASE_EXPIRED(false),

  /** A {@code cost.budget} counter reached zero (§9.6); never retryable. */
  BUDGET_EXHAUSTED(false),

  /** Malformed envelope or schema violation. */
  INVALID_REQUEST(false),

  /** Missing or invalid authentication (§6.1). */
  UNAUTHENTICATED(false),

  /** Unrecoverable runtime fault; always retryable. */
  INTERNAL_ERROR(true);

  private final boolean retryable;

  ErrorCode(boolean retryable) {
    this.retryable = retryable;
  }

  /**
   * Returns the default retryability of this code per §12.
   *
   * @return {@code true} if a retry may succeed
   */
  public boolean retryable() {
    return retryable;
  }

  /**
   * Returns the canonical wire string, which is the enum constant name itself.
   *
   * @return the wire string
   */
  @JsonValue
  public String wire() {
    return name();
  }

  /**
   * Resolves a wire string to a code, defaulting to {@link #INTERNAL_ERROR} for unknown codes so
   * decoding an error payload never fails.
   *
   * @param wire the wire code string
   * @return the matching code, or {@link #INTERNAL_ERROR}
   */
  @JsonCreator
  public static ErrorCode fromWire(String wire) {
    return Arrays.stream(values())
        .filter(c -> c.name().equals(wire))
        .findFirst()
        .orElse(INTERNAL_ERROR);
  }
}
