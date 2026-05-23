---
title: "Recipes"
sdk: java
spec_sections: []
kind: cookbook
since: "1.0.0"
---

# Recipes

Copy-paste patterns for common scenarios. Each recipe has a runnable
counterpart under [`recipes/`](../recipes/) or [`examples/`](../examples/).

## Idempotent retry

Submit with an `idempotency_key`; re-submitting the same key returns the
**same** accepted job without re-executing the agent:

```java
String key = UUID.randomUUID().toString(); // stable across retries

for (int attempt = 0; attempt <= 3; attempt++) {
    try {
        JobHandle handle = client.submit(ArcpClient.jobSubmit("my-agent", payload)
            .idempotencyKey(key)
            .build());
        return handle.result().get();
    } catch (ExecutionException ex) {
        if (ex.getCause() instanceof RetryableArcpException && attempt < 3) {
            Thread.sleep(Duration.ofSeconds(1L << attempt).toMillis());
            continue;
        }
        throw ex;
    }
}
```

`DUPLICATE_KEY` is **non-retryable** — it means the same key was used with
a *different* payload, which is a programming error.

Runnable: [`recipes/idempotent-retry/`](../recipes/idempotent-retry/)
(recipe) and [`examples/idempotent-retry/`](../examples/idempotent-retry/)
(example).

## Streaming events + final result

```java
JobHandle handle = client.submit(...);

// React to events as they arrive:
handle.events().subscribe(new Flow.Subscriber<EventBody>() {
    public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
    public void onNext(EventBody event) {
        if (event instanceof LogEvent log) System.out.println(log.message());
        if (event instanceof ProgressEvent p)
            System.out.printf("%d/%d %s%n", p.current(), p.total(), p.units());
    }
    public void onError(Throwable t) { t.printStackTrace(); }
    public void onComplete() { }
});

// Block for the final result:
JobResult result = handle.result().get();
System.out.println(result.result());
```

Runnable: [`examples/submit-and-stream/`](../examples/submit-and-stream/).

## Chunked result streaming

Agent side:

```java
(input, ctx) -> {
    String resultId = UUID.randomUUID().toString();
    for (int i = 0; i < 5; i++) {
        ctx.emitEvent(new ResultChunkEvent(
            resultId, i, "chunk-" + i, "utf-8", i < 4));
    }
    return JobOutcome.Success.streamed(resultId, 5, "five chunks");
}
```

Client side:

```java
ResultStream stream = handle.resultStream(resultId);
stream.chunks().forEach(chunk -> System.out.println(chunk.data()));
```

Runnable: [`examples/result-chunk/`](../examples/result-chunk/).

## Delegation (sub-agent dispatch)

```java
// Parent agent dispatches to a sub-agent, propagating a subset of its lease.
(input, ctx) -> {
    Lease subLease = Lease.builder()
        .allow("tool.call", "search:*")
        .build();
    JobHandle child = ctx.delegate("search-agent@1.0.0", input.payload(), subLease);
    return JobOutcome.Success.inline(child.result().get().result());
}
```

The runtime enforces that `subLease` is a strict subset of the parent
job's lease. See [guides/delegation.md](guides/delegation.md).

Runnable: [`examples/delegate/`](../examples/delegate/).

## OpenTelemetry tracing

Wrap the transport in the OTel decorator before passing to the client or
runtime:

```java
OpenTelemetry otel = GlobalOpenTelemetry.get();
Transport base = WebSocketTransport.connect(uri);
Transport traced = OtelTransport.wrap(base, otel);

ArcpClient client = ArcpClient.builder(traced).build();
```

Every envelope send/receive becomes a child span of the active trace
context. `trace_id` is propagated in the envelope (§11).

Runnable: [`examples/tracing/`](../examples/tracing/).

## Multi-agent cost budget

Submit multiple jobs each with a `cost.budget` lease, then enforce a
shared ceiling in the parent:

```java
// recipes/multi-agent-budget
Lease lease = Lease.builder()
    .allow("tool.call", "**")
    .budget("USD", new BigDecimal("5.00"))
    .build();

JobHandle h1 = client.submit(jobSubmit("agent-a", p1).lease(lease).build());
JobHandle h2 = client.submit(jobSubmit("agent-b", p2).lease(lease).build());

CompletableFuture.allOf(h1.result(), h2.result()).get();
```

Runnable: [`recipes/multi-agent-budget/`](../recipes/multi-agent-budget/).

## Email vendor lease extension

Extend the built-in lease namespaces with a custom `email.send` namespace
that your `BearerVerifier` grants:

```java
// recipes/email-vendor-leases
Lease lease = Lease.builder()
    .allow("email.send", "*@example.com")
    .build();

// Agent side:
ctx.authorize("email.send", "report@example.com"); // passes
ctx.authorize("email.send", "other@evil.com");      // PermissionDeniedException
```

Runnable: [`recipes/email-vendor-leases/`](../recipes/email-vendor-leases/).

## MCP skill bridge

Expose an ARCP agent as an MCP skill:

```java
// recipes/mcp-skill — bridges job.submit to an MCP tool call
ArcpRuntime runtime = ArcpRuntime.builder()
    .agent("mcp-bridge", "1.0.0", new McpSkillHandler(mcpClient))
    .build();
```

Runnable: [`recipes/mcp-skill/`](../recipes/mcp-skill/).

## Stream resume after disconnect

After a disconnect, resume from the last processed sequence number to
avoid reprocessing already-handled events:

```java
// recipes/stream-resume
long lastSeq = store.loadLastProcessedSeq(sessionId);
ArcpClient client = ArcpClient.builder(transport)
    .resumeToken(store.loadResumeToken(sessionId))
    .lastEventSeq(lastSeq)
    .build();
client.connect(Duration.ofSeconds(5));
```

Runnable: [`recipes/stream-resume/`](../recipes/stream-resume/).

## Vendor extensions

Emit a custom event kind:

```java
// Agent side:
ctx.emitEvent(VendorEvent.of("x-acme-profiling", Map.of("cpu_ms", 42)));

// Client side:
handle.events().subscribe(ev -> {
    if (ev instanceof VendorEvent v && "x-acme-profiling".equals(v.kind())) {
        System.out.println(v.extensions().get("cpu_ms"));
    }
});
```

See [guides/vendor-extensions.md](guides/vendor-extensions.md) and
[`examples/vendor-extensions/`](../examples/vendor-extensions/).
