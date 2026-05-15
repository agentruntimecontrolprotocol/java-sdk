package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record ToolCallEvent(
        String tool, JsonNode args, @JsonProperty("call_id") String callId) implements EventBody {
    @JsonCreator
    public ToolCallEvent(
            @JsonProperty("tool") String tool,
            @JsonProperty("args") JsonNode args,
            @JsonProperty("call_id") String callId) {
        this.tool = tool;
        this.args = args;
        this.callId = callId;
    }

    @Override
    public Kind kind() {
        return Kind.TOOL_CALL;
    }
}
