---
title: "arcp-core"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-core`

Foundation module. No runtime, no client — only the types shared by both.

## Dependency

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-core:1.0.0")
```

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.core.wire` | `Envelope`, `ArcpMapper` (Jackson config) |
| `dev.arcp.core.messages` | Sealed `Message` hierarchy (17 wire types) |
| `dev.arcp.core.events` | Sealed `EventBody` hierarchy (10 event kinds) |
| `dev.arcp.core.ids` | `MessageId`, `SessionId`, `JobId`, `TraceId` (ULID-based) |
| `dev.arcp.core.lease` | `Lease`, `LeaseConstraints`, `Capabilities` |
| `dev.arcp.core.agents` | `AgentRef` (parse `name@version`) |
| `dev.arcp.core.error` | `ErrorCode`, sealed `ArcpException` hierarchy |
| `dev.arcp.core.transport` | `Transport` SPI, `MemoryTransport`, `StdioTransport` |

## Key types

### `Envelope`

```java
public record Envelope(
    String arcp,          // protocol version, "1.1"
    MessageId id,
    Message.Type type,
    JsonNode payload,
    SessionId sessionId,  // null on session.hello
    TraceId traceId,      // optional
    JobId jobId,          // required on job-scoped messages
    Long eventSeq         // required on job.event
) {}
```

`Envelope.VERSION = "1.1"`.

### `ArcpMapper`

```java
ObjectMapper mapper = ArcpMapper.create();
```

Pre-configured: `JavaTimeModule`, `USE_BIG_DECIMAL_FOR_FLOATS`,
`FAIL_ON_UNKNOWN_PROPERTIES=false`, ISO-8601 dates, `NON_NULL`
serialization. See [architecture.md](../architecture.md#jackson-configuration).

### `Transport` SPI

```java
public interface Transport {
    void send(String frame) throws IOException;
    String receive() throws IOException;  // null = EOF
    void close();
}
```

### Sealed `Message` interface

17 wire message records, all `sealed`, dispatched exhaustively at compile
time. See [architecture.md](../architecture.md#message-taxonomy).

### Sealed `EventBody` interface

10 event kind records, all `sealed`. See
[architecture.md](../architecture.md#event-body-taxonomy).

### `ArcpException` hierarchy

`RetryableArcpException` / `NonRetryableArcpException` split.
See [guides/errors.md](../guides/errors.md).
