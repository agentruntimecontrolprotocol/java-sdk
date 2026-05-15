package dev.arcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class EnvelopeRoundTripTest {

    private final ObjectMapper mapper = ArcpMapper.create();

    @Test
    void envelopeWithSessionIdRoundTrips() throws Exception {
        SessionId sid = SessionId.of("sess_abc");
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("hello", "world");
        Envelope original = new Envelope(
                Envelope.VERSION,
                MessageId.of("m_1"),
                "session.hello",
                sid,
                null,
                null,
                null,
                payload);
        String json = mapper.writeValueAsString(original);
        Envelope parsed = mapper.readValue(json, Envelope.class);

        assertThat(parsed.arcp()).isEqualTo("1");
        assertThat(parsed.id()).isEqualTo(MessageId.of("m_1"));
        assertThat(parsed.sessionId()).isEqualTo(sid);
        assertThat(parsed.type()).isEqualTo("session.hello");
        assertThat(parsed.payload().get("hello").asText()).isEqualTo("world");
    }

    @Test
    void unknownTopLevelFieldsAreIgnored() throws Exception {
        String wire = "{\"arcp\":\"1\",\"id\":\"m_1\",\"type\":\"session.hello\","
                + "\"x-vendor.experimental\":42,\"payload\":{}}";
        Envelope parsed = mapper.readValue(wire, Envelope.class);
        assertThat(parsed.type()).isEqualTo("session.hello");
    }

    @Test
    void capabilityIntersectionIsCommutative() {
        var a = EnumSet.of(Feature.HEARTBEAT, Feature.ACK, Feature.SUBSCRIBE);
        var b = EnumSet.of(Feature.ACK, Feature.SUBSCRIBE, Feature.RESULT_CHUNK);
        assertThat(Capabilities.intersect(a, b))
                .containsExactlyInAnyOrder(Feature.ACK, Feature.SUBSCRIBE);
        assertThat(Capabilities.intersect(b, a))
                .containsExactlyInAnyOrder(Feature.ACK, Feature.SUBSCRIBE);
    }

    @Test
    void unknownFeaturesAreSilentlyDropped() throws Exception {
        String wire = "{\"encodings\":[\"json\"],\"features\":[\"heartbeat\",\"future-feature\"]}";
        Capabilities caps = mapper.readValue(wire, Capabilities.class);
        assertThat(caps.features()).containsExactly(Feature.HEARTBEAT);
    }
}
