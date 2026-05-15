package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.error.ErrorCode;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobError(
        @JsonProperty("final_status") String finalStatus,
        ErrorCode code,
        String message,
        boolean retryable,
        @Nullable JsonNode details)
        implements Message {

    public static final String ERROR = "error";
    public static final String CANCELLED = "cancelled";
    public static final String TIMED_OUT = "timed_out";

    @JsonCreator
    public static JobError fromJson(
            @JsonProperty("final_status") String finalStatus,
            @JsonProperty("code") ErrorCode code,
            @JsonProperty("message") String message,
            @JsonProperty("retryable") @Nullable Boolean retryable,
            @JsonProperty("details") @Nullable JsonNode details) {
        return new JobError(
                finalStatus,
                code,
                message,
                retryable != null ? retryable : code.retryable(),
                details);
    }

    @Override
    public Type kind() {
        return Type.JOB_ERROR;
    }
}
