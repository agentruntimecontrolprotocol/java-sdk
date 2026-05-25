---
title: "Observability"
sdk: java
spec_sections: ["11"]
kind: guide
since: "1.0.0"
---

# Observability (§11)

ARCP propagates `trace_id` end-to-end across every envelope. The
`arcp-otel` module wraps any transport in an OpenTelemetry decorator that
creates a span per envelope send/receive.

## Setup

```xml
<!-- pom.xml -->
<dependency><groupId>dev.arcp</groupId><artifactId>arcp-otel</artifactId><version>1.0.0</version></dependency>
<dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-api</artifactId><version>1.40.0</version></dependency>
<dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk</artifactId><version>1.40.0</version><scope>runtime</scope></dependency>
<dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId><version>1.40.0</version><scope>runtime</scope></dependency>
```

Wrap the transport before passing it to the client or runtime:

```java
OpenTelemetry otel = GlobalOpenTelemetry.get(); // or SDK-configured instance

// Client side:
Transport base    = WebSocketTransport.connect(serverUri);
Transport traced  = OtelTransport.wrap(base, otel);
ArcpClient client = ArcpClient.builder(traced).build();

// Runtime side:
Transport rBase    = pair[0];
Transport rTraced  = OtelTransport.wrap(rBase, otel);
runtime.accept(rTraced);
```

## Span model

| Span | Created on | Parent |
|---|---|---|
| `arcp.send` | Every `Transport.send(frame)` call | Current active context |
| `arcp.receive` | Every `Transport.receive()` call | Extracted `trace_id` from envelope |

`OtelTransport`:
1. On **send** — injects the current span context into the envelope's
   `trace_id` field before serializing.
2. On **receive** — extracts `trace_id` from the deserialized envelope and
   activates a child span.

## trace_id field

The `trace_id` envelope field carries an ARCP
[`TraceId`](../../arcp-core/src/main/java/dev/arcp/core/ids/TraceId.java).
It is optional in the spec; the OTel wrapper populates it on every send.

Propagation is end-to-end:
- Client → runtime: the client's active OTel trace context travels in
  `trace_id`.
- Runtime → client: events carry the same `trace_id` set at job start.
- Delegated sub-agent jobs inherit the parent's `trace_id`.

## Viewing traces

Point the OTel SDK exporter at any compatible backend (Jaeger, Tempo,
Honeycomb, Grafana Cloud...):

```java
SdkTracerProvider provider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(
        OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://localhost:4317")
            .build())
        .build())
    .build();
OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
    .setTracerProvider(provider)
    .buildAndRegisterGlobal();
```

## Runnable example

[`examples/tracing/`](../../examples/tracing/) — full end-to-end trace
with Jaeger via the OTLP exporter. Run with:

```
mvn -pl examples/tracing -am -DskipTests exec:java
```
