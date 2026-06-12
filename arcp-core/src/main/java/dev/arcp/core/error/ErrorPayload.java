package dev.arcp.core.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Wire shape of a §12 error: {@code { code, message, retryable, details? }}, carried by error
 * responses such as the {@code job.error} payload.
 *
 * @param code the canonical §12 error code
 * @param message human-readable detail
 * @param retryable whether retrying may succeed; defaults to {@link ErrorCode#retryable()} when
 *     absent on the wire
 * @param details implementation-defined detail object, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorPayload(
    ErrorCode code, String message, boolean retryable, @Nullable JsonNode details) {

  /** Canonical constructor requiring code and message. */
  public ErrorPayload {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }

  @JsonCreator
  static ErrorPayload from(
      @JsonProperty("code") ErrorCode code,
      @JsonProperty("message") String message,
      @JsonProperty("retryable") @Nullable Boolean retryable,
      @JsonProperty("details") @Nullable JsonNode details) {
    return new ErrorPayload(
        code, message, retryable != null ? retryable : code.retryable(), details);
  }

  /**
   * Creates a payload with the code's default §12 retryability and no details.
   *
   * @param code the error code
   * @param message human-readable detail
   * @return the payload
   */
  public static ErrorPayload of(ErrorCode code, String message) {
    return new ErrorPayload(code, message, code.retryable(), null);
  }
}
