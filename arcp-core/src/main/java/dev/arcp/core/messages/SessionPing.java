package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

public record SessionPing(String nonce, @JsonProperty("sent_at") Instant sentAt) implements Message {
    @JsonCreator
    public SessionPing(
            @JsonProperty("nonce") String nonce, @JsonProperty("sent_at") Instant sentAt) {
        this.nonce = Objects.requireNonNull(nonce, "nonce");
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
    }

    @Override
    public Type kind() {
        return Type.SESSION_PING;
    }
}
