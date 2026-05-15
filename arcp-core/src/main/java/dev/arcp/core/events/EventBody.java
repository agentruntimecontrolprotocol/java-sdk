package dev.arcp.core.events;

/**
 * Sealed type tag for the {@code body} of a §8.1 job event. The wire
 * {@code kind} string is the canonical discriminator; {@link Kind} mirrors it
 * as an enum for exhaustive switches in dispatch code.
 */
public sealed interface EventBody
        permits LogEvent,
                ThoughtEvent,
                ToolCallEvent,
                ToolResultEvent,
                StatusEvent,
                MetricEvent,
                ArtifactRefEvent,
                DelegateEvent,
                ProgressEvent,
                ResultChunkEvent {

    Kind kind();

    enum Kind {
        LOG("log"),
        THOUGHT("thought"),
        TOOL_CALL("tool_call"),
        TOOL_RESULT("tool_result"),
        STATUS("status"),
        METRIC("metric"),
        ARTIFACT_REF("artifact_ref"),
        DELEGATE("delegate"),
        PROGRESS("progress"),
        RESULT_CHUNK("result_chunk");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Kind fromWire(String wire) {
            return java.util.Arrays.stream(values())
                    .filter(k -> k.wire.equals(wire))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown event kind: " + wire));
        }
    }
}
