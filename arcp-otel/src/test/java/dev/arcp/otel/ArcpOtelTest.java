package dev.arcp.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.Envelope;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class ArcpOtelTest {

    @Test
    void wrappingEmitsSendAndReceiveSpans() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Tracer tracer = otel.getTracer("arcp-otel-test");

        MemoryTransport[] pair = MemoryTransport.pair();
        Transport sender = ArcpOtel.withTracing(pair[0], tracer);
        Transport receiver = ArcpOtel.withTracing(pair[1], tracer);

        CopyOnWriteArrayList<Envelope> received = new CopyOnWriteArrayList<>();
        receiver.incoming().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Envelope envelope) {
                received.add(envelope);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });

        Envelope env = new Envelope(
                Envelope.VERSION,
                MessageId.of("m_otel"),
                "session.ping",
                null, null, null, null,
                JsonNodeFactory.instance.objectNode());
        sender.send(env);

        await().atMost(Duration.ofSeconds(2)).until(() -> !received.isEmpty());
        assertThat(received.get(0).id()).isEqualTo(MessageId.of("m_otel"));

        // Two spans must exist: outbound "arcp.send.session.ping" and inbound "arcp.receive.session.ping".
        await().atMost(Duration.ofSeconds(2)).until(() -> exporter.getFinishedSpanItems().size() >= 2);
        var spans = exporter.getFinishedSpanItems();
        assertThat(spans).extracting(s -> s.getName())
                .contains("arcp.send.session.ping", "arcp.receive.session.ping");

        sender.close();
        receiver.close();
    }
}
