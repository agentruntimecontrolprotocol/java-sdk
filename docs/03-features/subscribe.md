---
title: "Job subscription"
sdk: java
spec_sections: ["7.6"]
order: 4
kind: feature
since: "1.0.0"
---

# Job subscription — §7.6

**Feature flag:** `subscribe`.

Subscription is a read-only attach to an existing job's event stream from
a different session (or later in the same session). Useful for dashboards,
auditors, or simply reconnecting an observer after a UI reload.

## Wire shape

```json
{ "type": "job.subscribe", "session_id": "sess_…",
  "payload": { "job_id": "job_…", "from_event_seq": 0, "history": true } }

{ "type": "job.subscribed", "session_id": "sess_…",
  "payload": { "job_id": "job_…", "current_status": "running",
               "agent": "code-refactor@2.0.0", "lease": {...},
               "subscribed_from": 1830, "replayed": true } }
```

After `job.subscribed`, `job.event` messages for the subscribed job
interleave with the session's normal event stream.

## Java surface

```java
Flow.Publisher<EventBody> stream =
    client.subscribe(jobId, SubscribeOptions.withHistory(0L));
stream.subscribe(new Flow.Subscriber<>() { … });
```

`SubscribeOptions.live()` skips history; `withHistory(fromSeq)` replays
buffered events with `seq > fromSeq` before live delivery begins.

## Authority constraints

A subscriber may observe; it may NOT cancel or otherwise mutate the job.
[`SessionLoop.handleCancel`](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java)
checks `rec.principal().equals(principal)` and silently drops cancel
attempts from non-submitter principals.

Authorization to subscribe: the runtime currently scopes subscribe by
principal (`rec.principal().equals(principal)`); cross-principal access
control is left to deployment policy and the `BearerVerifier` SPI.

## Example

[`arcp-client/src/test/java/dev/arcp/client/SubscribeReplayTest.java`](../../arcp-client/src/test/java/dev/arcp/client/SubscribeReplayTest.java)
exercises history replay end-to-end.
