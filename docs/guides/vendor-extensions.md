---
title: "Vendor extensions"
sdk: java
spec_sections: ["8.5"]
kind: guide
since: "1.0.0"
---

# Vendor extensions (§8.5)

ARCP reserves the `extensions` field in `job.event` payloads for custom
event kinds. Use it to add proprietary telemetry, profiling data, or
domain-specific events without modifying the core protocol.

## Naming convention

Custom event `kind` values **MUST** be prefixed with `x-` followed by a
reverse-domain-name vendor prefix (§8.5):

```
x-acme-profiling
x-mycompany-audit
x-vendor-custom
```

This prevents collisions with future ARCP-defined event kinds.

## Emitting a vendor event

```java
// Agent side:
import dev.arcp.core.events.VendorEvent;

ctx.emitEvent(VendorEvent.of(
    "x-acme-profiling",
    Map.of(
        "cpu_ms",    42,
        "memory_kb", 1024,
        "phase",     "embedding")));
```

`VendorEvent.of(kind, extensions)` wraps the map as a `JsonNode` and
sets `kind` in the wire payload.

## Receiving vendor events

Unknown `kind` values are deserialized as `VendorEvent` — the SDK never
drops them:

```java
handle.events().subscribe(new Flow.Subscriber<EventBody>() {
    public void onNext(EventBody ev) {
        if (ev instanceof VendorEvent v) {
            String kind = v.kind();
            JsonNode ext = v.extensions();
            if ("x-acme-profiling".equals(kind)) {
                int cpuMs = ext.get("cpu_ms").asInt();
                System.out.println("CPU ms: " + cpuMs);
            }
        }
    }
    // ... onSubscribe, onError, onComplete ...
});
```

Pattern-matching with Java 21 switch expressions:

```java
switch (ev) {
    case VendorEvent v when "x-acme-profiling".equals(v.kind()) ->
        processProfile(v.extensions());
    case LogEvent log -> System.out.println(log.message());
    default -> {}
}
```

## Extensions in other envelope fields

The `extensions` map can also appear on `job.submit` and `session.hello`
payloads for vendor-specific metadata (§8.5). Access via
`jobSubmit.extensions()` and `sessionHello.extensions()`.

## Interoperability

A conformant runtime that does not recognize a `kind` value delivers the
event unchanged to subscribers. This means:

- **Backward compatibility**: new vendor events don't break old clients.
- **Forward compatibility**: old runtimes forward events they don't
  understand.

## Runnable example

[`examples/vendor-extensions/`](../../examples/vendor-extensions/) —
emits `x-example-stats` events and reads them on the client side.
