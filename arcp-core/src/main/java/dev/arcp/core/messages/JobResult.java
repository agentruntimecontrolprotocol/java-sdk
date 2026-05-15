package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.ids.ResultId;
import org.jspecify.annotations.Nullable;

/**
 * §7 / §8.4 terminal job.result. When {@code resultId} is present the result is
 * the concatenation of streamed {@code result_chunk} bodies; otherwise the
 * inline {@code result} payload carries the result directly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResult(
        @JsonProperty("final_status") String finalStatus,
        @JsonProperty("result_id") @Nullable ResultId resultId,
        @JsonProperty("result_size") @Nullable Long resultSize,
        @Nullable JsonNode result,
        @Nullable String summary)
        implements Message {

    public static final String SUCCESS = "success";

    @JsonCreator
    public JobResult(
            @JsonProperty("final_status") String finalStatus,
            @JsonProperty("result_id") @Nullable ResultId resultId,
            @JsonProperty("result_size") @Nullable Long resultSize,
            @JsonProperty("result") @Nullable JsonNode result,
            @JsonProperty("summary") @Nullable String summary) {
        this.finalStatus = finalStatus;
        this.resultId = resultId;
        this.resultSize = resultSize;
        this.result = result;
        this.summary = summary;
    }

    @Override
    public Type kind() {
        return Type.JOB_RESULT;
    }
}
