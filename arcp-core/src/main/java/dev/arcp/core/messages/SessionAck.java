package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record SessionAck(@JsonProperty("last_processed_seq") long lastProcessedSeq)
        implements Message {
    @JsonCreator
    public SessionAck {}

    @Override
    public Type kind() {
        return Type.SESSION_ACK;
    }
}
