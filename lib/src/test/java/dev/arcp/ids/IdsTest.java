package dev.arcp.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.envelope.ARCPMapper;
import org.junit.jupiter.api.Test;

class IdsTest {

	private final ObjectMapper mapper = ARCPMapper.create();

	@Test
	void messageIdRoundTripsAsBareString() throws Exception {
		MessageId id = MessageId.of("01HF000000000000000000ABCD");
		String json = mapper.writeValueAsString(id);
		assertThat(json).isEqualTo("\"01HF000000000000000000ABCD\"");
		assertThat(mapper.readValue(json, MessageId.class)).isEqualTo(id);
	}

	@Test
	void blankRejected() {
		assertThatThrownBy(() -> MessageId.of("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SessionId.of(" ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nullRejected() {
		assertThatThrownBy(() -> new JobId(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void randomGeneratesDistinctIds() {
		assertThat(MessageId.random()).isNotEqualTo(MessageId.random());
		assertThat(SessionId.random()).isNotEqualTo(SessionId.random());
		assertThat(JobId.random()).isNotEqualTo(JobId.random());
		assertThat(StreamId.random()).isNotEqualTo(StreamId.random());
		assertThat(SubscriptionId.random()).isNotEqualTo(SubscriptionId.random());
		assertThat(TraceId.random()).isNotEqualTo(TraceId.random());
		assertThat(SpanId.random()).isNotEqualTo(SpanId.random());
		assertThat(LeaseId.random()).isNotEqualTo(LeaseId.random());
		assertThat(ArtifactId.random()).isNotEqualTo(ArtifactId.random());
	}

	@Test
	void idempotencyKeyHasNoRandomFactory() {
		IdempotencyKey k = IdempotencyKey.of("op-1");
		assertThat(k.asString()).isEqualTo("op-1");
	}
}
