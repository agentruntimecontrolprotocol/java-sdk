---
title: "Event acknowledgement"
sdk: java
spec_sections: ["6.5"]
order: 2
kind: feature
since: "1.0.0"
---

# Event acknowledgement — §6.5

**Feature flag:** `ack`.

The client periodically informs the runtime of the highest `event_seq` it
has processed. The runtime may use this to free buffered events earlier
than the resume-window timer would.

`session.ack` is advisory: resume still requires the client to present
`last_event_seq` independently, and the runtime does not assume an
unacknowledged event is unreceived.

## Wire shape

```json
{ "type": "session.ack", "session_id": "sess_…",
  "payload": { "last_processed_seq": 1827 } }
```

## Java surface

[`ArcpClient`](../../arcp-client/src/main/java/dev/arcp/client/ArcpClient.java)
emits `session.ack` automatically when `autoAck=true` (the default) and the
`ack` feature is negotiated. The cadence is bounded by
`Builder.ackInterval(Duration)` (default 200ms). The implementation is a
two-`AtomicLong` rate limiter (`lastSeenSeq` and `lastAckedSeq`) on the
scheduled executor.

Callers may opt out (`Builder.autoAck(false)`) and emit explicit
`client.ack(seq)` calls instead.

## When the feature is not negotiated

If either peer omits `ack`, the client never schedules emissions and the
runtime ignores any received `session.ack`. Resume-window-driven eviction
is the only buffer-management mechanism.
