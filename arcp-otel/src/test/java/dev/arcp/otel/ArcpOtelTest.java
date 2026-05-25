package dev.arcp.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.Envelope;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
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
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    Tracer tracer = otel.getTracer("arcp-otel-test");

    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport sender = ArcpOtel.withTracing(pair.runtime(), tracer);
    Transport receiver = ArcpOtel.withTracing(pair.client(), tracer);

    CopyOnWriteArrayList<Envelope> received = new CopyOnWriteArrayList<>();
    receiver
        .incoming()
        .subscribe(
            new Flow.Subscriber<>() {
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

    Envelope env =
        new Envelope(
            Envelope.VERSION,
            MessageId.of("m_otel"),
            "session.ping",
            null,
            null,
            null,
            null,
            JsonNodeFactory.instance.objectNode());
    try (Scope scope =
        Span.wrap(ArcpOtel.newSpanContext("11111111111111111111111111111111", "1111111111111111"))
            .makeCurrent()) {
      assertThat(scope).isNotNull();
      sender.send(env);
    }

    await().atMost(Duration.ofSeconds(2)).until(() -> !received.isEmpty());
    assertThat(received.get(0).id()).isEqualTo(MessageId.of("m_otel"));

    // Two spans must exist: outbound "arcp.send.session.ping" and inbound
    // "arcp.receive.session.ping".
    await().atMost(Duration.ofSeconds(2)).until(() -> exporter.getFinishedSpanItems().size() >= 2);
    var spans = exporter.getFinishedSpanItems();
    assertThat(spans)
        .extracting(s -> s.getName())
        .contains("arcp.send.session.ping", "arcp.receive.session.ping");

    sender.close();
    receiver.close();
  }

  @Test
  void tracingInjectsAndExtractsTraceContextExtensions() throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetry otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    Tracer tracer = otel.getTracer("arcp-otel-propagation-test");

    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport sender =
        ArcpOtel.withTracing(pair.runtime(), tracer, W3CTraceContextPropagator.getInstance());
    Transport receiver =
        ArcpOtel.withTracing(pair.client(), tracer, W3CTraceContextPropagator.getInstance());

    CopyOnWriteArrayList<Envelope> received = new CopyOnWriteArrayList<>();
    receiver.incoming().subscribe(collectingSubscriber(received));

    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.putObject("extensions").put("existing", "value");
    Envelope env =
        new Envelope(
            Envelope.VERSION,
            MessageId.of("m_propagated"),
            "job.submit",
            null,
            null,
            null,
            7L,
            payload);

    try (Scope scope =
        Span.wrap(ArcpOtel.newSpanContext("0123456789abcdef0123456789abcdef", "0123456789abcdef"))
            .makeCurrent()) {
      assertThat(scope).isNotNull();
      sender.send(env);
    }

    await().atMost(Duration.ofSeconds(2)).until(() -> !received.isEmpty());
    ObjectNode extensions = (ObjectNode) received.get(0).payload().get("extensions");
    assertThat(extensions.get("existing").asText()).isEqualTo("value");
    assertThat(extensions.get(ArcpOtel.EXTENSION_NAME).get("traceparent").asText())
        .contains("0123456789abcdef0123456789abcdef");

    await().atMost(Duration.ofSeconds(2)).until(() -> exporter.getFinishedSpanItems().size() >= 2);
    assertThat(exporter.getFinishedSpanItems())
        .extracting(span -> span.getAttributes().get(ArcpOtel.ATTR_EVENT_SEQ))
        .contains(7L);

    sender.close();
    receiver.close();
  }

  @Test
  void nonObjectExtensionsPayloadIsLeftUnchanged() throws Exception {
    Tracer tracer = OpenTelemetry.noop().getTracer("arcp-otel-noop");
    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport sender =
        ArcpOtel.withTracing(pair.runtime(), tracer, W3CTraceContextPropagator.getInstance());
    Transport receiver = ArcpOtel.withTracing(pair.client(), tracer);

    CopyOnWriteArrayList<Envelope> received = new CopyOnWriteArrayList<>();
    receiver.incoming().subscribe(collectingSubscriber(received));

    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("extensions", "not-an-object");
    Envelope env =
        new Envelope(
            Envelope.VERSION,
            MessageId.of("m_bad_extensions"),
            "session.ping",
            null,
            null,
            null,
            null,
            payload);

    sender.send(env);

    await().atMost(Duration.ofSeconds(2)).until(() -> !received.isEmpty());
    assertThat(received.get(0).payload()).isEqualTo(payload);

    sender.close();
    receiver.close();
  }

  private static Flow.Subscriber<Envelope> collectingSubscriber(
      CopyOnWriteArrayList<Envelope> received) {
    return new Flow.Subscriber<>() {
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
    };
  }
}
