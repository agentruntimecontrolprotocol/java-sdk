---
title: "Job events"
sdk: java
spec_sections: ["8", "8.2.1", "8.4", "8.5"]
kind: guide
since: "1.0.0"
---

# Job events (§8)

Events are the primary observability channel for a running job. All events
ride in `job.event` envelopes and are delivered in-order, with monotonic,
gap-free `event_seq` values per session.

## EventBody sealed hierarchy

Ten event kinds, sealed under
[`EventBody`](../../arcp-core/src/main/java/dev/arcp/core/events/EventBody.java).

| `kind` | Java record | Notes |
|---|---|---|
| `log` | `LogEvent` | Structured log line |
| `thought` | `ThoughtEvent` | Agent reasoning step |
| `tool_call` | `ToolCallEvent` | Outbound tool invocation |
| `tool_result` | `ToolResultEvent` | Inbound tool response |
| `status` | `StatusEvent` | Lifecycle phase change |
| `metric` | `MetricEvent` | Numeric measurement (drives `cost.budget`) |
| `artifact_ref` | `ArtifactRefEvent` | Reference to a produced artifact |
| `delegate` | `DelegateEvent` | Sub-agent dispatch record |
| `progress` | `ProgressEvent` | Current / total progress tuple (§8.2.1) |
| `result_chunk` | `ResultChunkEvent` | One piece of a chunked final result (§8.4) |

## Subscribing to events

```java
handle.events().subscribe(new Flow.Subscriber<EventBody>() {
    public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
    public void onNext(EventBody event) {
        switch (event) {
            case LogEvent log    -> System.out.println("[LOG] " + log.message());
            case ThoughtEvent t  -> System.out.println("[THOUGHT] " + t.text());
            case StatusEvent s   -> System.out.println("[STATUS] " + s.phase());
            case MetricEvent m   -> System.out.printf("[METRIC] %s = %s%n",
                                        m.name(), m.value());
            case ProgressEvent p -> System.out.printf("[PROGRESS] %d/%d %s%n",
                                        p.current(), p.total(), p.units());
            default              -> {}
        }
    }
    public void onError(Throwable t) { t.printStackTrace(); }
    public void onComplete() { /* job terminal */ }
});
```

`handle.events()` is a replaying publisher — late subscribers receive the
full event history accumulated so far.

## Emitting events from an agent

```java
(input, ctx) -> {
    ctx.emitEvent(new LogEvent("info", "starting work", Map.of()));
    ctx.emitEvent(new ThoughtEvent("I should process items in parallel"));
    ctx.emitEvent(new ToolCallEvent("search", objectNode().put("q", "ARCP")));
    ctx.emitEvent(new ToolResultEvent("search", objectNode().put("hits", 42)));
    ctx.emitEvent(new MetricEvent("tokens", new BigDecimal("1250"), "count"));
    ctx.emitEvent(new StatusEvent("processing"));
    ctx.emitEvent(new ArtifactRefEvent("report.pdf", "s3://bucket/report.pdf"));
    return JobOutcome.Success.inline(result);
}
```

## Progress events (§8.2.1)

Requires `progress` feature negotiated in the session:

```java
// Agent side:
ctx.emitEvent(new ProgressEvent(3, 10, "items", "batch 3 done"));
```

`ProgressEvent` compact constructor enforces:
- `current >= 0`
- `current <= total`

Client side — same `handle.events()` subscription:

```java
case ProgressEvent p -> System.out.printf("%d/%d %s%n",
    p.current(), p.total(), p.units());
```

Runnable: [`examples/progress/`](../../examples/progress/).

## Chunked result streaming (§8.4)

For large results that shouldn't wait until completion:

```java
// Agent side:
String resultId = UUID.randomUUID().toString();
for (int i = 0; i < chunks.size(); i++) {
    boolean more = i < chunks.size() - 1;
    ctx.emitEvent(new ResultChunkEvent(
        resultId, i, chunks.get(i), "utf-8", more));
}
return JobOutcome.Success.streamed(resultId, chunks.size(), "chunked result");
```

```java
// Client side:
ResultStream stream = handle.resultStream(resultId);
stream.chunks().forEach(chunk -> System.out.println(chunk.data()));
```

`ResultChunkEvent` has a monotonic `seq`, an `encoding` (`"utf-8"` or
`"base64"`), and a `more` flag — `false` on the final chunk.

Duplicate `seq` values throw `DuplicateChunkException`; encoding changes
mid-stream throw `EncodingMismatchException`.

Runnable: [`examples/result-chunk/`](../../examples/result-chunk/).

## Vendor extensions (§8.5)

Emit custom event kinds via the `extensions` payload field:

```java
// Agent side:
ctx.emitEvent(VendorEvent.of("x-acme-profiling", Map.of("cpu_ms", 42)));

// Client side:
case VendorEvent v when "x-acme-profiling".equals(v.kind()) ->
    System.out.println("CPU ms: " + v.extensions().get("cpu_ms"));
```

Unknown `kind` values are deserialized as `VendorEvent`. The `kind` field
MUST follow the `x-` prefix convention (§8.5) to avoid collisions with
future ARCP-defined event kinds.

See [guides/vendor-extensions.md](vendor-extensions.md) and
[`examples/vendor-extensions/`](../../examples/vendor-extensions/).
