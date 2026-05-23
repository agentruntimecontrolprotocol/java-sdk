---
title: "Sessions"
sdk: java
spec_sections: ["6", "6.1", "6.3", "6.4", "6.5", "6.6"]
kind: guide
since: "1.0.0"
---

# Sessions (§6)

A session is one transport connection with one negotiated feature set.
Everything — jobs, events, heartbeats — flows through a session.

## Handshake (§6.1–§6.2)

1. Client sends `session.hello` carrying:
   - `auth` — a bearer token
   - `features` — list of capability strings the client supports

2. Runtime verifies the token, intersects feature lists
   ([`Capabilities.intersect`](../../arcp-core/src/main/java/dev/arcp/core/capabilities/Capabilities.java)),
   and replies with `session.welcome` carrying:
   - `session_id` — the new session ID
   - `features` — the **negotiated** intersection
   - `resume_token` — opaque token for reconnect (§6.3)
   - `agents` — map of registered agent names → available versions

3. Both peers MUST NOT use any feature outside the negotiated intersection.

```java
ArcpClient client = ArcpClient.builder(transport)
    .bearer("my-token")
    .features("heartbeat", "ack", "progress")  // request capabilities
    .build();
client.connect(Duration.ofSeconds(5));  // blocks until session.welcome
```

The SDK automatically performs the handshake inside `connect`. The
negotiated feature set is accessible via `client.session().features()`.

## Available capabilities

| Feature string | Spec | What it enables |
|---|---|---|
| `heartbeat` | §6.4 | `session.ping` / `session.pong` liveness probing |
| `ack` | §6.5 | `session.ack` for buffer reclamation |
| `progress` | §8.2.1 | `ProgressEvent` emission |
| `result_chunk` | §8.4 | Chunked result streaming |
| `credentials` | §9.8 | `CredentialProvisioner` lifecycle |
| `model_use` | §9.7 | `model.use` lease namespace |

## Heartbeats (§6.4)

When both peers negotiate `heartbeat`, the runtime sends `session.ping`
after each idle interval. The client replies `session.pong`. Two missed
intervals on either side surface `HEARTBEAT_LOST`.

Configure the interval on the runtime side:

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .heartbeatIntervalSec(30)   // default: 30 s
    .build();
```

`HeartbeatLostException` is **retryable** — resume the session:

```java
} catch (ExecutionException ex) {
    if (ex.getCause() instanceof HeartbeatLostException) {
        reconnect(); // session.hello with resume_token
    }
}
```

The runtime's heartbeat scheduler runs on a platform-thread
`ScheduledExecutorService`; the ping dispatch runs on a virtual thread.

## Ack (§6.5)

A client with `ack` negotiated emits `session.ack { last_processed_seq }`
periodically so the runtime can free buffered events earlier. Advisory,
not flow-controlling.

By default the client auto-acks every 200 ms. Override the interval:

```java
ArcpClient client = ArcpClient.builder(transport)
    .ackInterval(Duration.ofSeconds(1))
    .build();
```

Disable auto-ack (manual mode):

```java
ArcpClient client = ArcpClient.builder(transport)
    .autoAck(false)
    .build();
// ...
client.ack(handle.lastReceivedSeq());
```

Internally two `AtomicLong` counters (`lastSeenSeq` / `lastAckedSeq`) gate
the ack so it is only sent when there is forward progress.

## Resume (§6.3)

After any disconnect, the client can reconnect and replay missed events:

```java
String resumeToken = client.session().resumeToken();
long lastSeq = client.session().lastReceivedSeq();

// ... transport reconnects ...

ArcpClient fresh = ArcpClient.builder(newTransport)
    .bearer("my-token")
    .resumeToken(resumeToken)
    .lastEventSeq(lastSeq)
    .build();
fresh.connect(Duration.ofSeconds(5));
// Events from lastSeq+1 onward are replayed
```

The runtime replays events from its in-memory
[`ResumeBuffer`](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/ResumeBuffer.java).
The default window is 60 s. After the window, `RESUME_WINDOW_EXPIRED` is
returned (non-retryable — start a fresh session).

See [resume.md](resume.md) for the full reconnect flow.

## List jobs (§6.6)

Retrieve all jobs associated with the current principal:

```java
Page<JobSummary> page = client.listJobs(JobFilter.all());
while (page.hasNext()) {
    page.items().forEach(s -> System.out.println(s.jobId() + " " + s.status()));
    page = page.next();
}
```

Jobs are scoped to the authenticated principal; no cross-principal data is
exposed.

## Session close

```java
client.close();  // sends session.bye, then closes transport
```

`ArcpClient` implements `AutoCloseable`; use try-with-resources for
guaranteed cleanup.
