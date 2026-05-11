# heartbeats

Dynamic peer-runtime federation. Workers register, take work via
`agent.delegate`, send heartbeats, and deregister cleanly. Heartbeat
loss reroutes the in-flight task to another worker — deduped by
`idempotency_key`.

## Before ARCP

Static worker pools with bespoke RPCs. The supervisor's "is this
worker alive?" answer comes from a TCP keepalive (lies during GC) or
a custom heartbeat that re-dispatch logic doesn't actually trust —
so re-dispatch either fires too eagerly (duplicate execution) or not
at all (stuck pipeline).

## With ARCP

```java
sched.scheduleAtFixedRate(() -> {
    for (Worker w : roster.stale(DEADLINE_SECONDS)) {
        Task task = jobsToTasks.remove(w.inFlightJob);
        if (task != null) dispatch(client, task, roster, jobsToTasks); // same idempotency_key
        roster.remove(w.workerId);
    }
}, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
```

`idempotency_key` makes re-dispatch safe: a worker that survived the
network blip will see the duplicate `agent.delegate` and dedupe.

## ARCP primitives

- Capability negotiation (per-role extension) — RFC §7, §21.
- `agent.delegate` — §14.
- Job lifecycle (accepted → started → heartbeat → terminal) — §10.
- Heartbeat loss recovery — §10.3 (`heartbeat_recovery: "block"`).
- `idempotency_key` for safe re-dispatch — §6.4.
- Trust levels — §15.3.

## File tour

- `Main.java` — boots supervisor + small worker pool with virtual threads.
- `Work.java` — actual job body. Stubbed.

## Variations

- Priority queues by tagging tasks with envelope `priority`.
- Per-worker quota tracked via `tokens.used` metrics emitted from
  worker sessions (RFC §17.3.1).
- Replace the in-process workers with separate processes on real
  hosts; the protocol shape doesn't change.
