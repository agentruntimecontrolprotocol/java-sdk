package dev.arcp.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.arcp.messages.control.ControlMessages;
import dev.arcp.messages.session.SessionMessages;

/**
 * Factory for a Jackson {@link ObjectMapper} configured for ARCP wire format
 * (RFC §6.1): JSR-310 {@code Instant} support, ISO-8601 timestamps, and a
 * custom {@link Envelope} deserializer that resolves the envelope-level
 * {@code type} discriminator via {@link MessageType#register(String, Class)}.
 *
 * <p>
 * {@link #create()} eagerly loads every built-in message family so the registry
 * is populated before any envelope is parsed.
 */
public final class ARCPMapper {

	static {
		ControlMessages.load();
		SessionMessages.load();
	}

	private ARCPMapper() {
	}

	/** @return a fresh, fully-configured mapper. Callers MAY cache it. */
	public static ObjectMapper create() {
		SimpleModule envelopes = new SimpleModule("arcp-envelopes");
		envelopes.addDeserializer(Envelope.class, new EnvelopeDeserializer());
		return new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(envelopes)
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	}
}
