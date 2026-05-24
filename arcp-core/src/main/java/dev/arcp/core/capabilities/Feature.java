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
  HEARTBEAT("heartbeat"),
  ACK("ack"),
  LIST_JOBS("list_jobs"),
  SUBSCRIBE("subscribe"),
  LEASE_EXPIRES_AT("lease_expires_at"),
  COST_BUDGET("cost.budget"),
  MODEL_USE("model.use"),
  PROVISIONED_CREDENTIALS("provisioned_credentials"),
  PROGRESS("progress"),
  RESULT_CHUNK("result_chunk"),
  AGENT_VERSIONS("agent_versions");

  private final String wire;

  Feature(String wire) {
    this.wire = wire;
  }

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

  @JsonCreator
  public static Optional<Feature> fromWire(String wire) {
    return Optional.ofNullable(BY_WIRE.get(wire));
  }
}
