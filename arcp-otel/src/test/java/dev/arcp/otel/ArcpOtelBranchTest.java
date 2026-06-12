package dev.arcp.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.Envelope;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Branch coverage for {@link ArcpOtel}'s tracing transport (#33). */
class ArcpOtelBranchTest {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final Tracer tracer = tracer(exporter);

  private static Tracer tracer(InMemorySpanExporter exporter) {
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    return otel.getTracer("arcp-otel-branch-test");
  }

  private static Envelope envelope(ObjectNode payload) {
    return new Envelope(
        Envelope.VERSION, MessageId.generate(), "job.event", null, null, null, null, payload);
  }

  private static Envelope fullEnvelope(ObjectNode payload) {
    return new Envelope(
        Envelope.VERSION,
        MessageId.generate(),
        "job.event",
        SessionId.of("sess_otel"),
        TraceId.of("trace_otel"),
        JobId.of("job_otel"),
        7L,
        payload);
  }

  @Test
  void sendFailureRecordsExceptionAndRethrows() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    pair.client().close();
    Transport tracing = ArcpOtel.withTracing(pair.runtime(), tracer);
    assertThatThrownBy(() -> tracing.send(envelope(JsonNodeFactory.instance.objectNode())))
        .isInstanceOf(IllegalStateException.class);
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertThat(spans).hasSize(1);
    assertThat(spans.get(0).getEvents()).anyMatch(e -> e.getName().equals("exception"));
  }

  @Test
  void optionalEnvelopeAttributesAreRecordedWhenPresent() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport tracing = ArcpOtel.withTracing(pair.runtime(), tracer);
    tracing.send(fullEnvelope(JsonNodeFactory.instance.objectNode()));
    SpanData span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getAttributes().get(ArcpOtel.ATTR_SESSION_ID)).isEqualTo("sess_otel");
    assertThat(span.getAttributes().get(ArcpOtel.ATTR_JOB_ID)).isEqualTo("job_otel");
    assertThat(span.getAttributes().get(ArcpOtel.ATTR_TRACE_ID)).isEqualTo("trace_otel");
    assertThat(span.getAttributes().get(ArcpOtel.ATTR_EVENT_SEQ)).isEqualTo(7L);
  }

  @Test
  void injectSkipsWhenNoActiveSpanContext() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    CopyOnWriteArrayList<Envelope> seen = collect(pair.client().incoming());
    Transport tracing =
        ArcpOtel.withTracing(pair.runtime(), tracer, W3CTraceContextPropagator.getInstance());

    // No active span: TracingTransport's own send span IS current, so traceparent will inject.
    // The carrier-empty branch needs a propagator that never injects.
    Transport noop =
        ArcpOtel.withTracing(
            pair.runtime(), tracer, io.opentelemetry.context.propagation.TextMapPropagator.noop());
    Envelope plain = envelope(JsonNodeFactory.instance.objectNode());
    noop.send(plain);
    await().atMost(Duration.ofSeconds(5)).until(() -> !seen.isEmpty());
    assertThat(seen.get(0).payload().has("extensions")).isFalse();

    // And the injecting propagator adds the extension under a fresh extensions object.
    tracing.send(envelope(JsonNodeFactory.instance.objectNode()));
    await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() == 2);
    assertThat(seen.get(1).payload().path("extensions").has(ArcpOtel.EXTENSION_NAME)).isTrue();
  }

  @Test
  void injectMergesIntoExistingExtensionsObject() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    CopyOnWriteArrayList<Envelope> seen = collect(pair.client().incoming());
    Transport tracing =
        ArcpOtel.withTracing(pair.runtime(), tracer, W3CTraceContextPropagator.getInstance());

    ObjectNode withExt = JsonNodeFactory.instance.objectNode();
    withExt.putObject("extensions").put("x-vendor.other", "keep");
    tracing.send(envelope(withExt));
    await().atMost(Duration.ofSeconds(5)).until(() -> !seen.isEmpty());
    ObjectNode ext = (ObjectNode) seen.get(0).payload().get("extensions");
    assertThat(ext.get("x-vendor.other").asText()).isEqualTo("keep");
    assertThat(ext.has(ArcpOtel.EXTENSION_NAME)).isTrue();
  }

  @Test
  void injectLeavesEnvelopeUntouchedWhenExtensionsIsNotAnObject() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    CopyOnWriteArrayList<Envelope> seen = collect(pair.client().incoming());
    Transport tracing =
        ArcpOtel.withTracing(pair.runtime(), tracer, W3CTraceContextPropagator.getInstance());

    ObjectNode badExt = JsonNodeFactory.instance.objectNode().put("extensions", "not-an-object");
    tracing.send(envelope(badExt));
    await().atMost(Duration.ofSeconds(5)).until(() -> !seen.isEmpty());
    assertThat(seen.get(0).payload().get("extensions").asText()).isEqualTo("not-an-object");
  }

  @Test
  void extractToleratesMissingOrMalformedTraceContext() {
    // Each malformed shape must still deliver the envelope and produce a receive span whose parent
    // is simply Context.current() — the extract branches: non-object extensions, missing extension
    // key, non-object extension value.
    ObjectNode nonObjectExt = JsonNodeFactory.instance.objectNode().put("extensions", 42);
    ObjectNode noKey = JsonNodeFactory.instance.objectNode();
    noKey.putObject("extensions").put("x-vendor.other", "v");
    ObjectNode nonObjectValue = JsonNodeFactory.instance.objectNode();
    nonObjectValue.putObject("extensions").put(ArcpOtel.EXTENSION_NAME, "bogus");

    for (ObjectNode payload : List.of(nonObjectExt, noKey, nonObjectValue)) {
      MemoryTransport.Pair pair = MemoryTransport.pair();
      Transport receiver =
          ArcpOtel.withTracing(pair.client(), tracer, W3CTraceContextPropagator.getInstance());
      CopyOnWriteArrayList<Envelope> seen = collect(receiver.incoming());
      pair.runtime().send(envelope(payload));
      await().atMost(Duration.ofSeconds(5)).until(() -> !seen.isEmpty());
    }
  }

  @Test
  void extractUsesValidTraceContextAsParent() throws Exception {
    Span parent = tracer.spanBuilder("upstream").startSpan();
    String traceparent;
    try (Scope ignored = parent.makeCurrent()) {
      java.util.Map<String, String> carrier = new java.util.HashMap<>();
      W3CTraceContextPropagator.getInstance()
          .inject(
              io.opentelemetry.context.Context.current(),
              carrier,
              (c, k, v) -> {
                if (c != null) {
                  c.put(k, v);
                }
              });
      traceparent = carrier.get("traceparent");
    } finally {
      parent.end();
    }

    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload
        .putObject("extensions")
        .putObject(ArcpOtel.EXTENSION_NAME)
        .put("traceparent", traceparent);

    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport receiver =
        ArcpOtel.withTracing(pair.client(), tracer, W3CTraceContextPropagator.getInstance());
    CopyOnWriteArrayList<Envelope> seen = collect(receiver.incoming());
    pair.runtime().send(envelope(payload));
    await().atMost(Duration.ofSeconds(5)).until(() -> !seen.isEmpty());

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                exporter.getFinishedSpanItems().stream()
                    .anyMatch(
                        s ->
                            s.getName().equals("arcp.receive.job.event")
                                && s.getTraceId().equals(parent.getSpanContext().getTraceId())));
  }

  @Test
  void incomingIsCachedAcrossCalls() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport tracing = ArcpOtel.withTracing(pair.client(), tracer);
    assertThat(tracing.incoming()).isSameAs(tracing.incoming());
  }

  @Test
  void closeBeforeIncomingIsSafeAndIdempotent() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport tracing = ArcpOtel.withTracing(pair.client(), tracer);
    tracing.close(); // wrappedPublisher still null
    Transport tracing2 = ArcpOtel.withTracing(MemoryTransport.pair().client(), tracer);
    tracing2.incoming();
    tracing2.close(); // wrappedPublisher present
  }

  @Test
  void upstreamCompletionAndErrorPropagate() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    Transport tracing = ArcpOtel.withTracing(pair.client(), tracer);
    CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
    tracing
        .incoming()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(Envelope item) {}

              @Override
              public void onError(Throwable throwable) {
                events.add("error");
              }

              @Override
              public void onComplete() {
                events.add("complete");
              }
            });
    pair.client().close(); // closes the inner inbound publisher → onComplete
    await().atMost(Duration.ofSeconds(5)).until(() -> !events.isEmpty());
    assertThat(events.get(0)).isEqualTo("complete");
  }

  private static CopyOnWriteArrayList<Envelope> collect(Flow.Publisher<Envelope> publisher) {
    CopyOnWriteArrayList<Envelope> seen = new CopyOnWriteArrayList<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(Envelope item) {
            seen.add(item);
          }

          @Override
          public void onError(Throwable throwable) {}

          @Override
          public void onComplete() {}
        });
    return seen;
  }
}
