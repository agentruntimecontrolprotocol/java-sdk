---
title: "arcp-otel"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-otel`

OpenTelemetry tracing adapter. Wraps any `Transport` to emit a span around
every inbound and outbound envelope.

## Dependency

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-otel:1.0.0")
// OTel SDK (choose your exporter):
implementation("io.opentelemetry:opentelemetry-sdk:1.x")
runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp:1.x")
```

`arcp-otel` depends on `opentelemetry-api` only; the SDK and exporters are
your choice.

## Key classes

### `ArcpOtel`

Static factory class. Wraps a `Transport` with tracing:

```java
// Basic — spans only, no W3C trace-context propagation:
Transport traced = ArcpOtel.withTracing(baseTransport, tracer);

// With W3C propagation (injects/extracts traceparent via envelope extensions):
Transport traced = ArcpOtel.withTracing(baseTransport, tracer, propagator);
```

Apply to the client transport:

```java
OpenTelemetry otel = GlobalOpenTelemetry.get();
Tracer tracer = otel.getTracer("my-service");

WebSocketTransport ws = WebSocketTransport.connect(uri);
Transport traced = ArcpOtel.withTracing(ws, tracer,
    otel.getPropagators().getTextMapPropagator());

ArcpClient client = ArcpClient.builder(traced)
    .bearer("token")
    .build();
```

Apply to the runtime transport:

```java
runtime.accept(ArcpOtel.withTracing(incomingTransport, tracer));
```

## Span model

Each span covers one envelope send or receive:

| Span name | Kind | Direction |
|---|---|---|
| `arcp.send.<type>` | `CLIENT` | outbound |
| `arcp.receive.<type>` | `SERVER` | inbound |

### Attributes

| Attribute | Type | Source |
|---|---|---|
| `arcp.direction` | `string` | `"out"` / `"in"` |
| `arcp.type` | `string` | `Envelope.type()` |
| `arcp.id` | `string` | `Envelope.id()` |
| `arcp.session_id` | `string` | `Envelope.sessionId()` if set |
| `arcp.job_id` | `string` | `Envelope.jobId()` if set |
| `arcp.trace_id` | `string` | `Envelope.traceId()` if set |
| `arcp.event_seq` | `long` | `Envelope.eventSeq()` if set |

Attribute names match the TypeScript `@arcp/middleware-otel` package so that
cross-SDK traces join correctly in your observability backend.

## W3C trace-context propagation

When a `TextMapPropagator` is supplied, the adapter injects
`traceparent` / `tracestate` into `payload.extensions["x-vendor.opentelemetry.tracecontext"]`
on outbound envelopes, and extracts them on inbound envelopes to continue the
trace as a child span.

This is an opt-in vendor extension (§8.5). Non-OTel runtimes silently ignore
the extension field.

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.otel` | `ArcpOtel` |

## Extension key constant

```java
String key = ArcpOtel.EXTENSION_NAME;
// → "x-vendor.opentelemetry.tracecontext"
```

## Related

- [Observability guide](../guides/observability.md)
- [Vendor extensions guide](../guides/vendor-extensions.md)
