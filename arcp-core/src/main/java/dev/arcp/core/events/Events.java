package dev.arcp.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Helpers to encode/decode an {@link EventBody} together with its {@code kind} discriminator. */
public final class Events {

    private Events() {}

    /** Serialize an event body into a flat JSON object with the {@code kind} field set. */
    public static com.fasterxml.jackson.databind.node.ObjectNode encode(
            ObjectMapper mapper, EventBody body) {
        com.fasterxml.jackson.databind.node.ObjectNode bodyNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(body);
        // No extra wrapping: callers compose into a JobEvent payload as needed.
        return bodyNode;
    }

    /** Decode a body from {@code kind} discriminator and the body JSON. */
    public static EventBody decode(ObjectMapper mapper, String kindWire, JsonNode body) {
        return switch (EventBody.Kind.fromWire(kindWire)) {
            case LOG -> mapper.convertValue(body, LogEvent.class);
            case THOUGHT -> mapper.convertValue(body, ThoughtEvent.class);
            case TOOL_CALL -> mapper.convertValue(body, ToolCallEvent.class);
            case TOOL_RESULT -> mapper.convertValue(body, ToolResultEvent.class);
            case STATUS -> mapper.convertValue(body, StatusEvent.class);
            case METRIC -> mapper.convertValue(body, MetricEvent.class);
            case ARTIFACT_REF -> mapper.convertValue(body, ArtifactRefEvent.class);
            case DELEGATE -> mapper.convertValue(body, DelegateEvent.class);
            case PROGRESS -> mapper.convertValue(body, ProgressEvent.class);
            case RESULT_CHUNK -> mapper.convertValue(body, ResultChunkEvent.class);
        };
    }
}
