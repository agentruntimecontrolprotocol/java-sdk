---
title: "Concepts"
sdk: java
spec_sections: ["5.1", "6", "7", "8", "9", "12"]
order: 2
kind: concept
since: "1.0.0"
---

# Concepts

## Envelope (§5.1)

Every wire message rides in one envelope:

| Field | Type | Required | Source |
|---|---|---|---|
| `arcp` | `"1"` | yes | [Envelope.VERSION](../arcp-core/src/main/java/dev/arcp/core/wire/Envelope.java) |
| `id` | ULID string | yes | [MessageId](../arcp-core/src/main/java/dev/arcp/core/ids/MessageId.java) |
| `type` | wire-typed string (e.g. `session.hello`, `job.event`) | yes | [Message.Type](../arcp-core/src/main/java/dev/arcp/core/messages/Message.java) |
| `payload` | JSON object | yes | typed per `type` |
| `session_id` | ULID-ish | conditional | populated after `session.welcome` |
| `trace_id` | hex string | optional | §11 trace propagation |
| `job_id` | ULID-ish | conditional | on job-scoped messages |
| `event_seq` | int64 | conditional | on `job.event`, monotonic per session |

Unknown top-level fields are silently ignored on parse
([`ArcpMapper` sets `FAIL_ON_UNKNOWN_PROPERTIES=false`](../arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java)).

## Sessions (§6)

A session is one transport plus one negotiated feature set.

- **Handshake**: client sends `session.hello` with auth + features list;
  runtime replies with `session.welcome` carrying the intersection
  (`Capabilities.intersect`) plus a fresh `resume_token`. After
  `session.welcome`, both peers MUST NOT use a feature outside the
  intersection.
- **Heartbeats**: when both peers negotiate `heartbeat`, the runtime emits
  `session.ping` on idle and treats two missed intervals as
  `HEARTBEAT_LOST`. Heartbeats do not advance `event_seq`.
- **Ack**: a client with `ack` negotiated may emit `session.ack {
  last_processed_seq }` periodically so the runtime can free buffered
  events. Advisory, not flow-controlling.
- **Resume**: a fresh `session.hello` carrying `resume_token` and
  `last_event_seq` from a prior session replays events from the in-memory
  [`ResumeBuffer`](../arcp-runtime/src/main/java/dev/arcp/runtime/session/ResumeBuffer.java).

## Jobs (§7)

A job is one agent invocation on the runtime side, one `JobHandle` on the
client side.

- **Submit**: `client.submit(jobSubmit)` blocks until `job.accepted` returns
  with the resolved `agent@version`, effective lease, and initial budget.
- **Events**: `handle.events()` is a buffered `Flow.Publisher<EventBody>`
  via [`ReplayingPublisher`](../arcp-client/src/main/java/dev/arcp/client/ReplayingPublisher.java).
  Subscribers attached after some events have been emitted still see the
  full history.
- **Result**: `handle.result()` is a `CompletableFuture<JobResult>` that
  completes when `job.result` arrives or fails with the matching
  `ArcpException` subclass on `job.error`.
- **Cancel**: `handle.cancel()` sends `job.cancel`; the runtime interrupts
  the worker virtual thread. Agents check `ctx.cancelled()` periodically.

State machine (per
[`JobRecord.Status`](../arcp-runtime/src/main/java/dev/arcp/runtime/session/JobRecord.java)):

```
PENDING → RUNNING → { SUCCESS | ERROR | CANCELLED | TIMED_OUT }
```

Terminal states are sticky; competing transitions lose to the first.

## Leases (§9)

A `Lease` is a bag of namespaces → glob patterns. Reserved namespaces in 1.0.0:

- `fs.read`, `fs.write` — filesystem path globs
- `net.fetch` — outbound URL globs
- `tool.call` — tool-name globs
- `agent.delegate` — sub-agent name globs
- `cost.budget` — `currency:amount` strings (§9.6)

Agents authorize per-operation via `ctx.authorize(namespace, pattern)`.
The runtime's [`LeaseGuard`](../arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java)
matches globs (`*` and `**` supported), checks `expires_at` if set, and
throws on miss.

`LeaseConstraints.expiresAt` (§9.5) must be ISO-8601 UTC with `Z` suffix and
in the future at submit. A `ScheduledExecutorService` watchdog terminates
the job at the deadline.

`cost.budget` (§9.6) maintains per-currency `BigDecimal` counters in
[`BudgetCounters`](../arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java).
Cost metrics emitted by the agent (`MetricEvent` with `name = cost.*` and
matching `unit`) decrement the counter via `BigDecimal.subtract` — exact, no
double-precision drift.

## Errors (§12)

Fifteen canonical [`ErrorCode`](../arcp-core/src/main/java/dev/arcp/core/error/ErrorCode.java)
values, each with a default `retryable` bit. The sealed
[`ArcpException`](../arcp-core/src/main/java/dev/arcp/core/error/ArcpException.java)
hierarchy splits at the top into `RetryableArcpException` and
`NonRetryableArcpException`, so a generic retry helper cannot accidentally
retry `LEASE_EXPIRED` or `BUDGET_EXHAUSTED`.

| Code | Retryable | Java type |
|---|---|---|
| `PERMISSION_DENIED` | no | `PermissionDeniedException` |
| `LEASE_SUBSET_VIOLATION` | no | `LeaseSubsetViolationException` |
| `JOB_NOT_FOUND` | no | `JobNotFoundException` |
| `DUPLICATE_KEY` | no | `DuplicateKeyException` |
| `AGENT_NOT_AVAILABLE` | no | `AgentNotAvailableException` |
| `AGENT_VERSION_NOT_AVAILABLE` | no | `AgentVersionNotAvailableException` |
| `CANCELLED` | no | `CancelledException` |
| `TIMEOUT` | yes | `TimeoutException` |
| `RESUME_WINDOW_EXPIRED` | no | `ResumeWindowExpiredException` |
| `HEARTBEAT_LOST` | yes | `HeartbeatLostException` |
| `LEASE_EXPIRED` | no | `LeaseExpiredException` |
| `BUDGET_EXHAUSTED` | no | `BudgetExhaustedException` |
| `INVALID_REQUEST` | no | `InvalidRequestException` |
| `UNAUTHENTICATED` | no | `UnauthenticatedException` |
| `INTERNAL_ERROR` | yes | `InternalErrorException` |
