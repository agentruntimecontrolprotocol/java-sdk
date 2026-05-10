package dev.arcp.envelope;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.ids.IdempotencyKey;
import dev.arcp.ids.JobId;
import dev.arcp.ids.MessageId;
import dev.arcp.ids.SessionId;
import dev.arcp.ids.SpanId;
import dev.arcp.ids.StreamId;
import dev.arcp.ids.SubscriptionId;
import dev.arcp.ids.TraceId;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Custom Jackson deserializer for {@link Envelope}. Resolves the envelope-level
 * {@code type} discriminator (RFC §6.1.1) to a concrete {@link MessageType}
 * variant via {@link MessageType#register(String, Class)}.
 */
final class EnvelopeDeserializer extends StdDeserializer<Envelope> {

	private static final long serialVersionUID = 1L;

	EnvelopeDeserializer() {
		super(Envelope.class);
	}

	@Override
	public Envelope deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		ObjectNode node = p.readValueAsTree();
		String type = requireText(node, "type");
		Class<? extends MessageType> payloadClass = MessageType.Registry.resolve(type);
		JsonNode payloadNode = node.get("payload");
		if (payloadNode == null) {
			throw ctxt.weirdStringException(type, Envelope.class, "missing payload");
		}
		MessageType payload = ctxt.readTreeAsValue(payloadNode, payloadClass);

		return new Envelope(requireText(node, "arcp"), readId(node, "id", MessageId.class, ctxt), type,
				readInstant(node, "timestamp", ctxt), readNullable(node, "session_id", SessionId.class, ctxt),
				readNullable(node, "job_id", JobId.class, ctxt), readNullable(node, "stream_id", StreamId.class, ctxt),
				readNullable(node, "subscription_id", SubscriptionId.class, ctxt),
				readNullable(node, "trace_id", TraceId.class, ctxt), readNullable(node, "span_id", SpanId.class, ctxt),
				readNullable(node, "parent_span_id", SpanId.class, ctxt),
				readNullable(node, "correlation_id", MessageId.class, ctxt),
				readNullable(node, "causation_id", MessageId.class, ctxt),
				readNullable(node, "idempotency_key", IdempotencyKey.class, ctxt),
				readNullable(node, "priority", Priority.class, ctxt), readText(node, "source"),
				readText(node, "target"), readExtensions(node), payload);
	}

	private static String requireText(ObjectNode node, String field) {
		JsonNode v = node.get(field);
		if (v == null || v.isNull()) {
			throw new IllegalArgumentException("envelope missing required field " + field);
		}
		return v.asText();
	}

	private static <T> T readId(ObjectNode node, String field, Class<T> cls, DeserializationContext ctxt)
			throws IOException {
		JsonNode v = node.get(field);
		if (v == null || v.isNull()) {
			throw new IllegalArgumentException("envelope missing required field " + field);
		}
		return ctxt.readTreeAsValue(v, cls);
	}

	private static Instant readInstant(ObjectNode node, String field, DeserializationContext ctxt) throws IOException {
		JsonNode v = node.get(field);
		if (v == null || v.isNull()) {
			throw new IllegalArgumentException("envelope missing required field " + field);
		}
		return ctxt.readTreeAsValue(v, Instant.class);
	}

	@Nullable
	private static <T> T readNullable(ObjectNode node, String field, Class<T> cls, DeserializationContext ctxt)
			throws IOException {
		JsonNode v = node.get(field);
		if (v == null || v.isNull()) {
			return null;
		}
		return ctxt.readTreeAsValue(v, cls);
	}

	@Nullable
	private static String readText(ObjectNode node, String field) {
		JsonNode v = node.get(field);
		return (v == null || v.isNull()) ? null : v.asText();
	}

	@Nullable
	private static Map<String, JsonNode> readExtensions(ObjectNode node) {
		JsonNode v = node.get("extensions");
		if (v == null || v.isNull()) {
			return null;
		}
		if (!v.isObject()) {
			throw new IllegalArgumentException("extensions must be an object");
		}
		Map<String, JsonNode> out = new HashMap<>();
		Iterator<Map.Entry<String, JsonNode>> it = v.properties().iterator();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> e = it.next();
			out.put(e.getKey(), e.getValue());
		}
		return out;
	}
}
