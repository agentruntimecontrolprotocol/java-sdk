package dev.arcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * §5.1 envelope round-trip property: any well-formed envelope serialises and
 * parses back to itself; injected unknown top-level fields are dropped.
 */
class EnvelopeRoundTripPropertyTest {

    private static final ObjectMapper MAPPER = ArcpMapper.create();

    @Property
    boolean envelopeRoundTrips(@ForAll("envelopes") Envelope original) throws Exception {
        String json = MAPPER.writeValueAsString(original);
        Envelope parsed = MAPPER.readValue(json, Envelope.class);
        return parsed.arcp().equals(original.arcp())
                && parsed.id().equals(original.id())
                && parsed.type().equals(original.type())
                && java.util.Objects.equals(parsed.sessionId(), original.sessionId())
                && java.util.Objects.equals(parsed.traceId(), original.traceId())
                && java.util.Objects.equals(parsed.jobId(), original.jobId())
                && java.util.Objects.equals(parsed.eventSeq(), original.eventSeq())
                && parsed.payload().equals(original.payload());
    }

    @Property
    void unknownTopLevelFieldsAreSilentlyDropped(@ForAll("envelopes") Envelope original)
            throws Exception {
        ObjectNode wire = (ObjectNode) MAPPER.valueToTree(original);
        // Inject three unknown fields; the parser must ignore all of them.
        wire.put("x-vendor.experimental", 42);
        wire.put("x-vendor.future-field", "ignored");
        wire.putNull("x-vendor.null-field");

        Envelope parsed = MAPPER.readValue(MAPPER.writeValueAsString(wire), Envelope.class);
        assertThat(parsed.arcp()).isEqualTo(original.arcp());
        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.type()).isEqualTo(original.type());
    }

    @Provide
    Arbitrary<Envelope> envelopes() {
        Arbitrary<String> ids = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(16);
        Arbitrary<String> types = Arbitraries.of(
                "session.hello", "session.welcome", "session.bye", "session.ping",
                "session.pong", "session.ack", "session.list_jobs", "session.jobs",
                "job.submit", "job.accepted", "job.event", "job.result", "job.error",
                "job.cancel", "job.subscribe", "job.subscribed", "job.unsubscribe");
        Arbitrary<MessageId> messageIds = ids.map(MessageId::of);
        Arbitrary<SessionId> sessionIds =
                ids.map(s -> SessionId.of("sess_" + s)).injectNull(0.2);
        Arbitrary<TraceId> traceIds =
                Arbitraries.strings().withCharRange('0', '9').ofLength(32)
                        .map(TraceId::of).injectNull(0.3);
        Arbitrary<JobId> jobIds =
                ids.map(s -> JobId.of("job_" + s)).injectNull(0.3);
        Arbitrary<Long> eventSeqs = Arbitraries.longs().between(0, 10_000).injectNull(0.4);

        return Arbitraries.create(() -> {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    payload.put("k", java.util.UUID.randomUUID().toString());
                    return payload;
                })
                .flatMap(payload -> messageIds.flatMap(id ->
                        types.flatMap(t ->
                                sessionIds.flatMap(sid ->
                                        traceIds.flatMap(tid ->
                                                jobIds.flatMap(jid ->
                                                        eventSeqs.map(seq ->
                                                                new Envelope(
                                                                        Envelope.VERSION,
                                                                        id, t, sid, tid, jid, seq,
                                                                        payload))))))));
    }
}
