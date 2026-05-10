package dev.arcp.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import dev.arcp.ids.IdempotencyKey;
import dev.arcp.ids.JobId;
import dev.arcp.ids.MessageId;
import dev.arcp.ids.SessionId;
import dev.arcp.ids.SpanId;
import dev.arcp.ids.StreamId;
import dev.arcp.ids.SubscriptionId;
import dev.arcp.ids.TraceId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * ARCP wire envelope (RFC §6.1).
 *
 * <p>
 * Required: {@link #arcp}, {@link #id}, {@link #type}, {@link #timestamp},
 * {@link #payload}. Conditional: {@link #sessionId}, {@link #jobId},
 * {@link #streamId}, {@link #subscriptionId}. Recommended: {@link #traceId},
 * {@link #spanId}, {@link #parentSpanId}. Optional: {@link #correlationId},
 * {@link #causationId}, {@link #idempotencyKey}, {@link #priority},
 * {@link #extensions}, {@link #source}, {@link #target}.
 *
 * <p>
 * The {@link #type} field is the envelope-level discriminator (RFC §6.1.1). The
 * compact constructor enforces {@code type.equals(payload.type())}.
 * Deserialization is performed by {@link EnvelopeDeserializer}, registered via
 * {@link ARCPMapper#create()}.
 */
@JsonDeserialize(using = EnvelopeDeserializer.class)
@JsonPropertyOrder({"arcp", "id", "type", "timestamp", "session_id", "job_id", "stream_id", "subscription_id",
		"trace_id", "span_id", "parent_span_id", "correlation_id", "causation_id", "idempotency_key", "priority",
		"source", "target", "extensions", "payload"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(@JsonProperty("arcp") String arcp, @JsonProperty("id") MessageId id,
		@JsonProperty("type") String type, @JsonProperty("timestamp") Instant timestamp,
		@JsonProperty("session_id") @Nullable SessionId sessionId, @JsonProperty("job_id") @Nullable JobId jobId,
		@JsonProperty("stream_id") @Nullable StreamId streamId,
		@JsonProperty("subscription_id") @Nullable SubscriptionId subscriptionId,
		@JsonProperty("trace_id") @Nullable TraceId traceId, @JsonProperty("span_id") @Nullable SpanId spanId,
		@JsonProperty("parent_span_id") @Nullable SpanId parentSpanId,
		@JsonProperty("correlation_id") @Nullable MessageId correlationId,
		@JsonProperty("causation_id") @Nullable MessageId causationId,
		@JsonProperty("idempotency_key") @Nullable IdempotencyKey idempotencyKey,
		@JsonProperty("priority") @Nullable Priority priority, @JsonProperty("source") @Nullable String source,
		@JsonProperty("target") @Nullable String target,
		@JsonProperty("extensions") @Nullable Map<String, JsonNode> extensions,
		@JsonProperty("payload") MessageType payload) {

	/** Wire-format protocol version this SDK speaks. */
	public static final String PROTOCOL_VERSION = "1.0";

	public Envelope {
		Objects.requireNonNull(arcp, "arcp");
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(timestamp, "timestamp");
		Objects.requireNonNull(payload, "payload");
		if (!type.equals(payload.type())) {
			throw new IllegalArgumentException("envelope type=" + type + " mismatches payload type=" + payload.type());
		}
	}

	/**
	 * Build a minimally-valid envelope with current protocol version and the given
	 * timestamp. The {@code type} discriminator is derived from
	 * {@code payload.type()}.
	 */
	public static Envelope of(MessageId id, Instant timestamp, MessageType payload) {
		Objects.requireNonNull(payload, "payload");
		return new Envelope(PROTOCOL_VERSION, id, payload.type(), timestamp, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, payload);
	}
}
