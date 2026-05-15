package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.error.ErrorPayload;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResultEvent(
        @JsonProperty("call_id") String callId,
        @Nullable JsonNode result,
        @Nullable ErrorPayload error)
        implements EventBody {

    @JsonCreator
    public ToolResultEvent(
            @JsonProperty("call_id") String callId,
            @JsonProperty("result") @Nullable JsonNode result,
            @JsonProperty("error") @Nullable ErrorPayload error) {
        this.callId = callId;
        this.result = result;
        this.error = error;
    }

    @Override
    public Kind kind() {
        return Kind.TOOL_RESULT;
    }
}
