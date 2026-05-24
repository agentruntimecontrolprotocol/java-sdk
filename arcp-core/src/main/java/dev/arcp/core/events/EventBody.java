package dev.arcp.core.events;

/**
 * Sealed type tag for the {@code body} of a §8.1 job event. The wire {@code kind} string is the
 * canonical discriminator; {@link Kind} mirrors it as an enum for exhaustive switches in dispatch
 * code.
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

    private static final java.util.Map<String, Kind> BY_WIRE;

    static {
      java.util.Map<String, Kind> m = new java.util.HashMap<>();
      for (Kind k : values()) {
        m.put(k.wire, k);
      }
      BY_WIRE = java.util.Collections.unmodifiableMap(m);
    }

    public static Kind fromWire(String wire) {
      Kind k = BY_WIRE.get(wire);
      if (k == null) {
        throw new IllegalArgumentException("unknown event kind: " + wire);
      }
      return k;
    }
  }
}
