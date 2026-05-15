package dev.arcp.core.capabilities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum Feature {
    HEARTBEAT("heartbeat"),
    ACK("ack"),
    LIST_JOBS("list_jobs"),
    SUBSCRIBE("subscribe"),
    LEASE_EXPIRES_AT("lease_expires_at"),
    COST_BUDGET("cost.budget"),
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

    @JsonCreator
    public static Optional<Feature> fromWire(String wire) {
        return Arrays.stream(values())
                .filter(f -> f.wire.equals(wire))
                .findFirst();
    }
}
