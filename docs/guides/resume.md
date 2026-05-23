---
title: "Resume"
sdk: java
spec_sections: ["6.3"]
kind: guide
since: "1.0.0"
---

# Resume (§6.3)

Resume lets a client reconnect to the same logical session after a
disconnect and replay any missed events, without re-submitting jobs.

## How it works

1. `session.welcome` carries a `resume_token` — an opaque string generated
   by the runtime.
2. The runtime's
   [`ResumeBuffer`](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/ResumeBuffer.java)
   retains all `job.event`, `job.result`, and `job.error` frames for a
   configurable window (default 60 s).
3. On reconnect, the client sends a new `session.hello` with `resume_token`
   and `last_event_seq` (the highest sequence number the client processed).
4. The runtime replays all buffered frames with `event_seq > last_event_seq`
   before allowing new events.

## Reconnect flow

```java
// 1. Save token + seq before the connection drops
String token = client.session().resumeToken();
long lastSeq  = client.session().lastReceivedSeq();

// ... transport failure — reconnect the underlying connection ...
Transport newTransport = WebSocketTransport.connect(serverUri);

// 2. Resume
ArcpClient fresh = ArcpClient.builder(newTransport)
    .bearer(myBearerToken)
    .resumeToken(token)
    .lastEventSeq(lastSeq)
    .build();
fresh.connect(Duration.ofSeconds(5));

// 3. Re-attach handles to running jobs (events replay automatically)
JobHandle handle = fresh.reattach(myJobId);
JobResult result = handle.result().get();
```

`client.reattach(jobId)` sends `job.subscribe` and returns a `JobHandle`
backed by the replayed event stream.

## Resume window

The runtime discards buffered frames after a configurable TTL:

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .resumeWindowSec(300)   // default: 60 s; set to 5 min
    .build();
```

If the client reconnects after the window, the runtime returns
`RESUME_WINDOW_EXPIRED` (non-retryable). In that case:

1. Start a fresh session (new `session.hello`, no `resume_token`).
2. Re-subscribe to any jobs that are still running:
   `client.subscribe(jobId, SubscribeOptions.live())`.
3. Events emitted before the reconnect are lost — design agents to
   checkpoint state externally if durability is required.

## Ack interplay

The `ack` feature tells the runtime which events have been processed,
allowing earlier eviction from the buffer. With `ack` negotiated, the
client periodically emits `session.ack { last_processed_seq }`. Frames
at or below `last_processed_seq` are eligible for early reclamation.

See [guides/sessions.md](sessions.md#ack).

## Runnable example

[`examples/resume/`](../../examples/resume/) — demonstrates a simulated
disconnect mid-stream and the reconnect/replay flow.

[`recipes/stream-resume/`](../../recipes/stream-resume/) — shows how to
persist the resume token and last-seq to durable storage between process
restarts.
