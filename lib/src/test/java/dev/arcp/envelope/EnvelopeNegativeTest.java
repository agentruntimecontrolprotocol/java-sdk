package dev.arcp.envelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.ids.MessageId;
import dev.arcp.messages.control.Ping;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EnvelopeNegativeTest {

	private final ObjectMapper mapper = ARCPMapper.create();

	@Test
	void typeMustMatchPayloadType() {
		assertThatThrownBy(() -> new Envelope("1.0", MessageId.random(), "pong", Instant.now(), null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, new Ping("x")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mismatches");
	}

	@Test
	void unknownWireTypeRejected() {
		String wire = "{\"arcp\":\"1.0\",\"id\":\"X\",\"timestamp\":\"2026-05-10T00:00:00Z\","
				+ "\"type\":\"bogus.unknown\",\"payload\":{}}";
		assertThatThrownBy(() -> mapper.readValue(wire, Envelope.class)).hasMessageContaining("unknown message type");
	}

	@Test
	void missingTypeRejected() {
		String wire = "{\"arcp\":\"1.0\",\"id\":\"X\",\"timestamp\":\"2026-05-10T00:00:00Z\"," + "\"payload\":{}}";
		assertThatThrownBy(() -> mapper.readValue(wire, Envelope.class))
				.hasMessageContaining("missing required field type");
	}

	@Test
	void extensionsMustBeObject() {
		String wire = "{\"arcp\":\"1.0\",\"id\":\"X\",\"timestamp\":\"2026-05-10T00:00:00Z\","
				+ "\"type\":\"ping\",\"extensions\":[],\"payload\":{\"nonce\":\"q\"}}";
		assertThatThrownBy(() -> mapper.readValue(wire, Envelope.class))
				.hasMessageContaining("extensions must be an object");
	}

	@Test
	void messageTypeRegistryKnowsBuiltIns() {
		assertThat(MessageType.isKnown("ping")).isTrue();
		assertThat(MessageType.isKnown("pong")).isTrue();
		assertThat(MessageType.isKnown("nope.v1")).isFalse();
	}

	@Test
	void messageTypeRegistryRegister() {
		MessageType.register("ping", Ping.class); // idempotent
		assertThat(MessageType.isKnown("ping")).isTrue();
	}

	@Test
	void priorityWireRoundTrip() throws Exception {
		String s = mapper.writeValueAsString(Priority.HIGH);
		assertThat(s).isEqualTo("\"high\"");
		assertThat(mapper.readValue(s, Priority.class)).isEqualTo(Priority.HIGH);
	}
}
