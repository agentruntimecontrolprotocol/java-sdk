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

  /**
   * Returns the {@link Kind} discriminator matching this body's wire {@code kind} string.
   *
   * @return the event kind
   */
  Kind kind();

  /** Event {@code kind} discriminator per §8.2. */
  enum Kind {
    /** {@code log}: diagnostic output, body {@code { level, message }}. */
    LOG("log"),

    /** {@code thought}: agent reasoning trace, body {@code { text }}. */
    THOUGHT("thought"),

    /** {@code tool_call}: tool invocation, body {@code { tool, args, call_id }}. */
    TOOL_CALL("tool_call"),

    /** {@code tool_result}: tool outcome, body {@code { call_id, result | error }}. */
    TOOL_RESULT("tool_result"),

    /** {@code status}: job phase transition, body {@code { phase, message? }}. */
    STATUS("status"),

    /** {@code metric}: numeric measurement, body {@code { name, value, unit?, dimensions? }}. */
    METRIC("metric"),

    /**
     * {@code artifact_ref}: out-of-band artifact pointer, body {@code { uri, content_type,
     * byte_size?, sha256? }}.
     */
    ARTIFACT_REF("artifact_ref"),

    /** {@code delegate}: §10 sub-agent delegation. */
    DELEGATE("delegate"),

    /**
     * {@code progress}: §8.2.1 advisory progress, body {@code { current, total?, units?, message?
     * }}.
     */
    PROGRESS("progress"),

    /** {@code result_chunk}: §8.4 streamed result fragment. */
    RESULT_CHUNK("result_chunk");

    private final String wire;

    Kind(String wire) {
      this.wire = wire;
    }

    /**
     * Returns the canonical wire {@code kind} string.
     *
     * @return the wire string
     */
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

    /**
     * Resolves a wire {@code kind} string to its enum constant.
     *
     * @param wire the wire kind string
     * @return the matching kind
     * @throws IllegalArgumentException if the kind is unknown
     */
    public static Kind fromWire(String wire) {
      Kind k = BY_WIRE.get(wire);
      if (k == null) {
        throw new IllegalArgumentException("unknown event kind: " + wire);
      }
      return k;
    }
  }
}
