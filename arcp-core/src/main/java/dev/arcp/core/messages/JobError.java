package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.error.ErrorCode;
import org.jspecify.annotations.Nullable;

/**
 * Terminal {@code job.error} payload. Per §7.3, {@code BUDGET_EXHAUSTED} and {@code LEASE_EXPIRED}
 * surface with {@code final_status: "error"}; cancellation ends with code {@code CANCELLED} and
 * {@code final_status: "cancelled"} (§7.4).
 *
 * @param finalStatus terminal status ({@code final_status}): {@link #ERROR}, {@link #CANCELLED}, or
 *     {@link #TIMED_OUT}
 * @param code the canonical §12 error code
 * @param message human-readable detail
 * @param retryable whether resubmitting may succeed; defaults to the code's §12 retryability
 * @param details implementation-defined detail object, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobError(
    @JsonProperty("final_status") String finalStatus,
    ErrorCode code,
    String message,
    boolean retryable,
    @Nullable JsonNode details)
    implements Message {

  /** {@code final_status} for jobs that failed. */
  public static final String ERROR = "error";

  /** {@code final_status} for jobs cancelled by the client (§7.4). */
  public static final String CANCELLED = "cancelled";

  /** {@code final_status} for jobs that exceeded {@code max_runtime_sec}. */
  public static final String TIMED_OUT = "timed_out";

  /**
   * Jackson factory applying the code's §12 default when {@code retryable} is absent.
   *
   * @param finalStatus terminal status ({@code final_status})
   * @param code the §12 error code
   * @param message human-readable detail
   * @param retryable the wire {@code retryable} flag, or {@code null} to default from the code
   * @param details implementation-defined detail object, or {@code null}
   * @return the decoded payload
   */
  @JsonCreator
  public static JobError fromJson(
      @JsonProperty("final_status") String finalStatus,
      @JsonProperty("code") ErrorCode code,
      @JsonProperty("message") String message,
      @JsonProperty("retryable") @Nullable Boolean retryable,
      @JsonProperty("details") @Nullable JsonNode details) {
    return new JobError(
        finalStatus, code, message, retryable != null ? retryable : code.retryable(), details);
  }

  @Override
  public Type kind() {
    return Type.JOB_ERROR;
  }
}
