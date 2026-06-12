package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * §8.2 {@code tool_call} event body: the agent invoked a tool.
 *
 * @param tool name of the invoked tool
 * @param args tool arguments as JSON
 * @param callId correlation id echoed by the matching {@code tool_result} ({@code call_id})
 */
public record ToolCallEvent(String tool, JsonNode args, @JsonProperty("call_id") String callId)
    implements EventBody {
  /** Canonical constructor. */
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
