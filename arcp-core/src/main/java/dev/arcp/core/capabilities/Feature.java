package dev.arcp.core.capabilities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enumeration of all optional ARCP protocol features. Each constant carries the canonical {@code
 * wire} string used in capability negotiation; unknown wire strings are surfaced through {@link
 * #fromWire(String)} as {@link Optional#empty()}.
 */
public enum Feature {
  /**
   * §6.4 liveness: {@code session.ping}/{@code session.pong} exchanged at least every {@code
   * heartbeat_interval_sec}.
   */
  HEARTBEAT("heartbeat"),

  /** §6.5 event acknowledgement via {@code session.ack} with {@code last_processed_seq}. */
  ACK("ack"),

  /** §6.6 read-only job inventory via {@code session.list_jobs} / {@code session.jobs}. */
  LIST_JOBS("list_jobs"),

  /** §7.6 attaching to jobs from other or earlier sessions via {@code job.subscribe}. */
  SUBSCRIBE("subscribe"),

  /** §9.5 lease expiration via {@code lease_constraints.expires_at}. */
  LEASE_EXPIRES_AT("lease_expires_at"),

  /** §9.6 budget capability: {@code cost.budget} lease entries with runtime spend enforcement. */
  COST_BUDGET("cost.budget"),

  /** §9.7 model capability: {@code model.use} lease patterns restricting model access. */
  MODEL_USE("model.use"),

  /** §9.8 short-lived upstream credentials delivered on {@code job.accepted}. */
  PROVISIONED_CREDENTIALS("provisioned_credentials"),

  /** §8.2.1 advisory {@code progress} job events. */
  PROGRESS("progress"),

  /** §8.4 result streaming via {@code result_chunk} events and {@code result_id}. */
  RESULT_CHUNK("result_chunk"),

  /** §7.5 agent versioning: {@code name@version} submission and version inventory. */
  AGENT_VERSIONS("agent_versions");

  private final String wire;

  Feature(String wire) {
    this.wire = wire;
  }

  /**
   * Returns the canonical wire string carried in the capabilities {@code features} array.
   *
   * @return the wire string
   */
  @JsonValue
  public String wire() {
    return wire;
  }

  private static final Map<String, Feature> BY_WIRE;

  static {
    Map<String, Feature> m = new HashMap<>();
    for (Feature f : values()) {
      m.put(f.wire, f);
    }
    BY_WIRE = Collections.unmodifiableMap(m);
  }

  /**
   * Resolves a wire string to a feature. Unknown strings yield {@link Optional#empty()} so
   * unrecognized features are dropped rather than failing capability decoding.
   *
   * @param wire the wire feature string
   * @return the matching feature, or empty when unrecognized
   */
  @JsonCreator
  public static Optional<Feature> fromWire(String wire) {
    return Optional.ofNullable(BY_WIRE.get(wire));
  }
}
