---
title: "API overview"
sdk: java
spec_sections: []
order: 1
kind: reference
since: "1.0.0"
---

# API overview

## Package map

| Package | Purpose |
|---|---|
| `dev.arcp.core.wire` | `Envelope`, `ArcpMapper` |
| `dev.arcp.core.messages` | Sealed `Message` taxonomy and per-message records |
| `dev.arcp.core.events` | Sealed `EventBody` taxonomy |
| `dev.arcp.core.error` | `ErrorCode`, sealed `ArcpException` |
| `dev.arcp.core.capabilities` | `Feature`, `Capabilities`, `AgentDescriptor` |
| `dev.arcp.core.auth` | `Auth`, `Principal`, `BearerVerifier` |
| `dev.arcp.core.ids` | ULID-backed `MessageId`, `SessionId`, `JobId`, `TraceId`, `ResultId` |
| `dev.arcp.core.lease` | `Lease`, `LeaseConstraints` |
| `dev.arcp.core.transport` | `Transport` SPI + `MemoryTransport` |
| `dev.arcp.core.agents` | `AgentRef` (§7.5 grammar) |
| `dev.arcp.runtime` | `ArcpRuntime` builder |
| `dev.arcp.runtime.session` | `SessionLoop`, `JobRecord`, `ResumeBuffer` |
| `dev.arcp.runtime.agent` | `Agent` SPI, `AgentRegistry`, `JobContext`, `JobInput`, `JobOutcome` |
| `dev.arcp.runtime.lease` | `LeaseGuard`, `BudgetCounters` |
| `dev.arcp.runtime.heartbeat` | `HeartbeatTracker` |
| `dev.arcp.runtime.idempotency` | `IdempotencyStore` |
| `dev.arcp.client` | `ArcpClient`, `JobHandle`, `Session`, `Page`, `SubscribeOptions`, `ResultStream`, `WebSocketTransport` |
| `dev.arcp.runtime.jetty` | `ArcpJettyServer`, `ArcpJettyEndpoint` |
| `dev.arcp.middleware.jakarta` | `ArcpJakartaAdapter`, `ArcpJakartaEndpoint` |
| `dev.arcp.middleware.spring` | `ArcpSpringBootAutoConfiguration`, `ArcpWebSocketHandler`, `ArcpSpringBootProperties` |
| `dev.arcp.middleware.vertx` | `ArcpVertxHandler` |
| `dev.arcp.otel` | `ArcpOtel.withTracing(transport, tracer)` |
| `dev.arcp.tck` | `TckProvider`, `ConformanceSuite` |

## Client lifecycle

```
ArcpClient.builder(transport).bearer(token).build()
    → client.connect(timeout)                         → Session
    → client.submit(jobSubmit)                        → JobHandle
        → handle.events()                             → Flow.Publisher<EventBody>
        → handle.result()                             → CompletableFuture<JobResult>
        → handle.cancel()
    → client.listJobs(filter)                         → Page<JobSummary>
    → client.subscribe(jobId, options)                → Flow.Publisher<EventBody>
    → client.ack(seq)                                 (auto-emitted by default)
    → client.close()                                  → AutoCloseable
```

## Runtime lifecycle

```
ArcpRuntime.builder()
    .verifier(bearerVerifier)
    .agent(name, version, agent)
    .heartbeatIntervalSec(30)
    .build()
    .accept(transport)                                → SessionLoop
    .close()                                          → AutoCloseable
```

## Threading

- Virtual threads (JDK 21 `Executors.newVirtualThreadPerTaskExecutor`)
  drive every per-job worker, every per-transport publisher dispatch, and
  the WebSocket reassembly loop.
- One `ScheduledExecutorService` per runtime fires heartbeat ticks and
  lease-expiry watchdogs (a virtual-thread `ScheduledExecutorService` is
  not provided by the JDK; the workaround uses platform threads for the
  scheduler with virtual-thread runnables).
- The client side runs a similar scheduler for ack rate-limiting and the
  inbound-idle watchdog.
- `StructuredTaskScope` is NOT used in published bytecode: it is preview
  in JDK 21 and finalised in JDK 25 with a different shape; the SDK
  targets `--release 21`.

## Closing

Every consumer-visible top-level type (`ArcpClient`, `ArcpRuntime`,
`Transport`, `ArcpJettyServer`) implements `AutoCloseable`. `close()`
cancels scheduled futures, drains and closes per-session publishers,
sends `session.bye` (client side), and tears down the transport.
