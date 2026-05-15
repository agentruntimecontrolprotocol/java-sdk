---
title: "Heartbeats"
sdk: java
spec_sections: ["6.4"]
order: 1
kind: feature
since: "1.0.0"
---

# Heartbeats — §6.4

**Feature flag:** `heartbeat`.

When both peers negotiate `heartbeat`, the runtime advertises
`heartbeat_interval_sec` in `session.welcome` and schedules a `session.ping`
whenever the inbound channel has been idle for one interval. The receiver
replies with `session.pong { ping_nonce, received_at }`.

A peer that observes no inbound traffic for two consecutive intervals
treats the connection as dead, closes the transport, and surfaces
`HEARTBEAT_LOST`. The runtime does NOT terminate jobs on heartbeat loss —
the session can be resumed within its `resume_window_sec`.

## Wire shape

```json
{ "type": "session.ping", "session_id": "sess_…",
  "payload": { "nonce": "p_…", "sent_at": "2026-05-13T19:42:13Z" } }

{ "type": "session.pong", "session_id": "sess_…",
  "payload": { "ping_nonce": "p_…", "received_at": "2026-05-13T19:42:13.020Z" } }
```

## Java surface

- [`HeartbeatTracker`](../../arcp-runtime/src/main/java/dev/arcp/runtime/heartbeat/HeartbeatTracker.java)
  tracks last-inbound on the runtime side.
- [`SessionLoop.tickHeartbeat`](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java)
  is the scheduled callback; the runtime owns one
  `ScheduledExecutorService` shared across sessions.
- The client side runs an equivalent watchdog in
  [`ArcpClient.watchHeartbeat`](../../arcp-client/src/main/java/dev/arcp/client/ArcpClient.java).

`ArcpRuntime.builder().heartbeatIntervalSec(int)` configures the interval.

## Example

[`examples/heartbeat/`](../../examples/heartbeat/) negotiates a 1-second
interval, stays idle for 2.5 seconds, and asserts the session is still
active.

## When the feature is not negotiated

If either peer omits `heartbeat` from its capabilities list, neither side
schedules pings and the watchdog is disabled. Sessions are kept alive
purely by transport-level liveness (TCP keepalives, WebSocket protocol
pings).
