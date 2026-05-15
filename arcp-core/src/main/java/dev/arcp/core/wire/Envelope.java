package dev.arcp.core.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * ARCP wire envelope per spec §5.1.
 *
 * <p>Required fields: {@code arcp}, {@code id}, {@code type}, {@code payload}.
 * Conditional fields: {@code session_id}, {@code trace_id}, {@code job_id},
 * {@code event_seq}. Unknown top-level fields MUST be ignored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(
        String arcp,
        MessageId id,
        String type,
        @JsonProperty("session_id") @Nullable SessionId sessionId,
        @JsonProperty("trace_id") @Nullable TraceId traceId,
        @JsonProperty("job_id") @Nullable JobId jobId,
        @JsonProperty("event_seq") @Nullable Long eventSeq,
        JsonNode payload) {

    public static final String VERSION = "1";

    public Envelope {
        Objects.requireNonNull(arcp, "arcp");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }

    @JsonCreator
    public static Envelope create(
            @JsonProperty("arcp") String arcp,
            @JsonProperty("id") MessageId id,
            @JsonProperty("type") String type,
            @JsonProperty("session_id") @Nullable SessionId sessionId,
            @JsonProperty("trace_id") @Nullable TraceId traceId,
            @JsonProperty("job_id") @Nullable JobId jobId,
            @JsonProperty("event_seq") @Nullable Long eventSeq,
            @JsonProperty("payload") JsonNode payload) {
        return new Envelope(arcp, id, type, sessionId, traceId, jobId, eventSeq, payload);
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public static final class Builder {
        private MessageId id = MessageId.generate();
        private final String type;
        private @Nullable SessionId sessionId;
        private @Nullable TraceId traceId;
        private @Nullable JobId jobId;
        private @Nullable Long eventSeq;
        private @Nullable JsonNode payload;

        Builder(String type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder id(MessageId id) {
            this.id = id;
            return this;
        }

        public Builder sessionId(@Nullable SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder traceId(@Nullable TraceId traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder jobId(@Nullable JobId jobId) {
            this.jobId = jobId;
            return this;
        }

        public Builder eventSeq(@Nullable Long eventSeq) {
            this.eventSeq = eventSeq;
            return this;
        }

        public Builder payload(JsonNode payload) {
            this.payload = payload;
            return this;
        }

        public Envelope build() {
            Objects.requireNonNull(payload, "payload");
            return new Envelope(VERSION, id, type, sessionId, traceId, jobId, eventSeq, payload);
        }
    }
}
