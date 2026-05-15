package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

public record SessionPong(
        @JsonProperty("ping_nonce") String pingNonce,
        @JsonProperty("received_at") Instant receivedAt)
        implements Message {
    @JsonCreator
    public SessionPong(
            @JsonProperty("ping_nonce") String pingNonce,
            @JsonProperty("received_at") Instant receivedAt) {
        this.pingNonce = Objects.requireNonNull(pingNonce, "pingNonce");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    @Override
    public Type kind() {
        return Type.SESSION_PONG;
    }
}
