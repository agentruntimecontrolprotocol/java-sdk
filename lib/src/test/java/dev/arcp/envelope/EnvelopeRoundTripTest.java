package dev.arcp.envelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.ids.IdempotencyKey;
import dev.arcp.ids.JobId;
import dev.arcp.ids.MessageId;
import dev.arcp.ids.SessionId;
import dev.arcp.ids.SpanId;
import dev.arcp.ids.StreamId;
import dev.arcp.ids.SubscriptionId;
import dev.arcp.ids.TraceId;
import dev.arcp.messages.control.Ping;
import dev.arcp.messages.control.Pong;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvelopeRoundTripTest {

	private final ObjectMapper mapper = ARCPMapper.create();

	@Test
	void minimalEnvelopeRoundTrip() throws Exception {
		Envelope env = Envelope.of(MessageId.random(), Instant.parse("2026-05-10T12:00:00Z"), new Ping("n1"));
		String json = mapper.writeValueAsString(env);
		Envelope back = mapper.readValue(json, Envelope.class);

		assertThat(back).isEqualTo(env);
		assertThat(back.type()).isEqualTo("ping");
		assertThat(back.payload()).isInstanceOf(Ping.class);
	}

	@Test
	void fullEnvelopeRoundTripPreservesAllFields() throws Exception {
		ObjectNode ext = JsonNodeFactory.instance.objectNode();
		ext.set("optional", JsonNodeFactory.instance.booleanNode(true));
		ext.set("retries", new IntNode(3));
		Envelope env = new Envelope(Envelope.PROTOCOL_VERSION, MessageId.of("01HF000000000000000000MSGA"), "pong",
				Instant.parse("2026-05-10T12:00:00.000Z"), SessionId.of("01HF000000000000000000SESS"),
				JobId.of("01HF000000000000000000JOB1"), StreamId.of("01HF000000000000000000STR1"),
				SubscriptionId.of("01HF000000000000000000SUB1"), TraceId.of("01HF000000000000000000TRC1"),
				SpanId.of("01HF000000000000000000SPN1"), SpanId.of("01HF000000000000000000SPN0"),
				MessageId.of("01HF000000000000000000COR1"), MessageId.of("01HF000000000000000000CAU1"),
				IdempotencyKey.of("op-42"), Priority.CRITICAL, "src.runtime", "dst.client",
				Map.of("arcpx.example.demo.v1", (JsonNode) ext), new Pong("n1"));

		String json = mapper.writeValueAsString(env);
		Envelope back = mapper.readValue(json, Envelope.class);

		assertThat(back).isEqualTo(env);
		assertThat(json).contains("\"type\":\"pong\"");
		assertThat(json).contains("\"priority\":\"critical\"");
	}

	@Test
	void typeDiscriminatorDictatesPolymorphicVariant() throws Exception {
		String wire = "{\"arcp\":\"1.0\",\"id\":\"X\",\"timestamp\":\"2026-05-10T00:00:00Z\","
				+ "\"type\":\"pong\",\"payload\":{\"nonce\":\"q\"}}";
		Envelope back = mapper.readValue(wire, Envelope.class);
		assertThat(back.payload()).isInstanceOf(Pong.class);
		assertThat(((Pong) back.payload()).nonce()).isEqualTo("q");
	}

	@Test
	void compactConstructorRejectsNulls() {
		assertThatThrownBy(() -> Envelope.of(MessageId.random(), Instant.now(), null))
				.isInstanceOf(NullPointerException.class);
	}
}
