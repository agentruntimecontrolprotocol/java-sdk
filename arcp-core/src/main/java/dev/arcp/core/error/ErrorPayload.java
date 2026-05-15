package dev.arcp.core.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorPayload(
        ErrorCode code,
        String message,
        boolean retryable,
        @Nullable JsonNode details) {

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

    public static ErrorPayload of(ErrorCode code, String message) {
        return new ErrorPayload(code, message, code.retryable(), null);
    }
}
