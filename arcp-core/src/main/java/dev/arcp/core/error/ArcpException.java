package dev.arcp.core.error;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public abstract sealed class ArcpException extends Exception
        permits RetryableArcpException, NonRetryableArcpException {

    private final ErrorCode code;

    protected ArcpException(ErrorCode code, String message) {
        this(code, message, null);
    }

    protected ArcpException(ErrorCode code, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }

    public abstract boolean retryable();

    public static ArcpException from(ErrorPayload p) {
        return switch (p.code()) {
            case PERMISSION_DENIED -> new PermissionDeniedException(p.message());
            case LEASE_SUBSET_VIOLATION -> new LeaseSubsetViolationException(p.message());
            case JOB_NOT_FOUND -> new JobNotFoundException(p.message());
            case DUPLICATE_KEY -> new DuplicateKeyException(p.message());
            case AGENT_NOT_AVAILABLE -> new AgentNotAvailableException(p.message());
            case AGENT_VERSION_NOT_AVAILABLE ->
                    new AgentVersionNotAvailableException(p.message());
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
