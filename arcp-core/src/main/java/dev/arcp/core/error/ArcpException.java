package dev.arcp.core.error;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Base of the sealed exception hierarchy mirroring the §12 error taxonomy. Every instance carries
 * an {@link ErrorCode}; the {@link RetryableArcpException} / {@link NonRetryableArcpException}
 * branches fix the {@link #retryable()} flag.
 */
public abstract sealed class ArcpException extends Exception
    permits RetryableArcpException, NonRetryableArcpException {

  /** The §12 error code carried by this exception. */
  private final ErrorCode code;

  /**
   * Creates an exception without a cause.
   *
   * @param code the §12 error code
   * @param message human-readable detail
   */
  protected ArcpException(ErrorCode code, String message) {
    this(code, message, null);
  }

  /**
   * Creates an exception with an optional cause.
   *
   * @param code the §12 error code
   * @param message human-readable detail
   * @param cause the underlying cause, or {@code null}
   */
  protected ArcpException(ErrorCode code, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  /**
   * Returns the §12 error code.
   *
   * @return the error code
   */
  public ErrorCode code() {
    return code;
  }

  /**
   * Returns whether retrying the failed operation may succeed, per the §12 taxonomy.
   *
   * @return {@code true} if a retry may succeed
   */
  public abstract boolean retryable();

  /**
   * Maps a wire error payload to its typed exception.
   *
   * @param p the decoded error payload
   * @return the exception subtype matching {@code p.code()}
   */
  public static ArcpException from(ErrorPayload p) {
    return switch (p.code()) {
      case PERMISSION_DENIED -> new PermissionDeniedException(p.message());
      case LEASE_SUBSET_VIOLATION -> new LeaseSubsetViolationException(p.message());
      case JOB_NOT_FOUND -> new JobNotFoundException(p.message());
      case DUPLICATE_KEY -> new DuplicateKeyException(p.message());
      case AGENT_NOT_AVAILABLE -> new AgentNotAvailableException(p.message());
      case AGENT_VERSION_NOT_AVAILABLE -> new AgentVersionNotAvailableException(p.message());
      case CANCELLED -> new CancelledException(p.message());
      case TIMEOUT -> new TimeoutException(p.message());
      case RESUME_WINDOW_EXPIRED -> new ResumeWindowExpiredException(p.message());
      case HEARTBEAT_LOST -> new HeartbeatLostException(p.message());
      case LEASE_EXPIRED -> new LeaseExpiredException(p.message());
      case BUDGET_EXHAUSTED -> new BudgetExhaustedException(p.message());
      case INVALID_REQUEST -> new InvalidRequestException(p.message());
      case UNAUTHENTICATED -> new UnauthenticatedException(p.message());
      case INTERNAL_ERROR -> new InternalErrorException(p.message());
    };
  }
}
