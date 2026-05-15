package dev.arcp.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.Envelope;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Wraps a {@link Transport} to emit an OpenTelemetry span around each inbound
 * and outbound envelope, mirroring TS {@code @arcp/middleware-otel} attribute
 * names so cross-SDK traces line up.
 */
public final class ArcpOtel {

    public static final String EXTENSION_NAME = "x-vendor.opentelemetry.tracecontext";

    public static final AttributeKey<String> ATTR_DIRECTION =
            AttributeKey.stringKey("arcp.direction");
    public static final AttributeKey<String> ATTR_TYPE = AttributeKey.stringKey("arcp.type");
    public static final AttributeKey<String> ATTR_ID = AttributeKey.stringKey("arcp.id");
    public static final AttributeKey<String> ATTR_SESSION_ID =
            AttributeKey.stringKey("arcp.session_id");
    public static final AttributeKey<String> ATTR_JOB_ID = AttributeKey.stringKey("arcp.job_id");
    public static final AttributeKey<String> ATTR_TRACE_ID =
            AttributeKey.stringKey("arcp.trace_id");
    public static final AttributeKey<Long> ATTR_EVENT_SEQ =
            AttributeKey.longKey("arcp.event_seq");

    private ArcpOtel() {}

    public static Transport withTracing(Transport inner, Tracer tracer) {
        return withTracing(inner, tracer, null);
    }

    public static Transport withTracing(
            Transport inner, Tracer tracer, @Nullable TextMapPropagator propagator) {
        return new TracingTransport(inner, tracer, propagator);
    }

    private static final class TracingTransport implements Transport {

        private final Transport inner;
        private final Tracer tracer;
        private final @Nullable TextMapPropagator propagator;
        private final AtomicReference<@Nullable SubmissionPublisher<Envelope>> wrappedPublisher =
                new AtomicReference<>();

        TracingTransport(Transport inner, Tracer tracer, @Nullable TextMapPropagator propagator) {
            this.inner = Objects.requireNonNull(inner, "inner");
            this.tracer = Objects.requireNonNull(tracer, "tracer");
            this.propagator = propagator;
        }

        @Override
        public void send(Envelope envelope) {
            Envelope toSend = envelope;
            Span span = tracer.spanBuilder("arcp.send." + envelope.type())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAllAttributes(attributesFor("out", envelope))
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                if (propagator != null) {
                    toSend = injectTraceContext(envelope);
                }
                inner.send(toSend);
            } catch (RuntimeException e) {
                span.recordException(e);
                throw e;
            } finally {
                span.end();
            }
        }

        @Override
        public Flow.Publisher<Envelope> incoming() {
            SubmissionPublisher<Envelope> wrap = wrappedPublisher.get();
            if (wrap != null) {
                return wrap;
            }
            SubmissionPublisher<Envelope> fresh = new SubmissionPublisher<>();
            if (!wrappedPublisher.compareAndSet(null, fresh)) {
                fresh.close();
                return Objects.requireNonNull(wrappedPublisher.get());
            }
            inner.incoming().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Envelope envelope) {
                    Context parent = propagator != null
                            ? extractTraceContext(envelope)
                            : Context.current();
                    Span span = tracer.spanBuilder("arcp.receive." + envelope.type())
                            .setSpanKind(SpanKind.SERVER)
                            .setParent(parent)
                            .setAllAttributes(attributesFor("in", envelope))
                            .startSpan();
                    try (Scope ignored = span.makeCurrent()) {
                        fresh.submit(envelope);
                    } finally {
                        span.end();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    fresh.closeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    fresh.close();
                }
            });
            return fresh;
        }

        @Override
        public void close() {
            SubmissionPublisher<Envelope> w = wrappedPublisher.get();
            if (w != null) {
                w.close();
            }
            inner.close();
        }

        private Attributes attributesFor(String direction, Envelope envelope) {
            var b = Attributes.builder()
                    .put(ATTR_DIRECTION, direction)
                    .put(ATTR_TYPE, envelope.type())
                    .put(ATTR_ID, envelope.id().asString());
            if (envelope.sessionId() != null) {
                b.put(ATTR_SESSION_ID, envelope.sessionId().asString());
            }
            if (envelope.jobId() != null) {
                b.put(ATTR_JOB_ID, envelope.jobId().asString());
            }
            if (envelope.traceId() != null) {
                b.put(ATTR_TRACE_ID, envelope.traceId().asString());
            }
            if (envelope.eventSeq() != null) {
                b.put(ATTR_EVENT_SEQ, envelope.eventSeq());
            }
            return b.build();
        }

        private Envelope injectTraceContext(Envelope envelope) {
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(Context.current(), carrier, TextMapSetterImpl.INSTANCE);
            if (carrier.isEmpty()) {
                return envelope;
            }
            ObjectNode payload = envelope.payload().deepCopy();
            ObjectNode ext = (ObjectNode) payload.path("extensions");
            if (ext.isMissingNode()) {
                ext = payload.putObject("extensions");
            } else if (!payload.has("extensions")) {
                ext = payload.putObject("extensions");
            }
            ObjectNode otelNode = ext.putObject(EXTENSION_NAME);
            for (var e : carrier.entrySet()) {
                otelNode.put(e.getKey(), e.getValue());
            }
            return new Envelope(
                    envelope.arcp(),
                    envelope.id(),
                    envelope.type(),
                    envelope.sessionId(),
                    envelope.traceId(),
                    envelope.jobId(),
                    envelope.eventSeq(),
                    payload);
        }

        private Context extractTraceContext(Envelope envelope) {
            ObjectNode ext = (ObjectNode) envelope.payload().path("extensions");
            if (ext.isMissingNode() || !ext.has(EXTENSION_NAME)) {
                return Context.current();
            }
            ObjectNode otelNode = (ObjectNode) ext.get(EXTENSION_NAME);
            Map<String, String> carrier = new HashMap<>();
            otelNode.fieldNames().forEachRemaining(k -> carrier.put(k, otelNode.get(k).asText()));
            return propagator.extract(Context.current(), carrier, TextMapGetterImpl.INSTANCE);
        }

        private enum TextMapSetterImpl implements TextMapSetter<Map<String, String>> {
            INSTANCE;

            @Override
            public void set(@Nullable Map<String, String> carrier, String key, String value) {
                if (carrier != null) {
                    carrier.put(key, value);
                }
            }
        }

        private enum TextMapGetterImpl
                implements io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>> {
            INSTANCE;

            @Override
            public Iterable<String> keys(Map<String, String> carrier) {
                return carrier.keySet();
            }

            @Override
            public @Nullable String get(@Nullable Map<String, String> carrier, String key) {
                return carrier == null ? null : carrier.get(key);
            }
        }
    }

    /** Build an opaque {@link SpanContext} for tests. */
    public static SpanContext newSpanContext(String traceId, String spanId) {
        return SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
    }
}
