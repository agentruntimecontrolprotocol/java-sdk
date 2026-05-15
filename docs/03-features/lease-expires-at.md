---
title: "Lease expiration"
sdk: java
spec_sections: ["9.5"]
order: 7
kind: feature
since: "1.0.0"
---

# Lease expiration — §9.5

**Feature flag:** `lease_expires_at`.

A job's lease MAY carry an `expires_at` timestamp via
`lease_constraints`. Operations attempted at or after the timestamp fail
with `LEASE_EXPIRED`.

`expires_at` MUST be ISO-8601 UTC with the `Z` suffix and MUST be in the
future at submit. Past or offset-only values are rejected with
`INVALID_REQUEST` at the SDK seam — not at JSON parse, so the rejection
cites the validated field rather than a parse error.

## Wire shape

```json
"lease_constraints": { "expires_at": "2026-05-13T23:42:00Z" }
```

## Java surface

```java
LeaseConstraints constraints = LeaseConstraints.of(
    Instant.parse("2026-05-13T23:42:00Z"));
JobSubmit submit = ArcpClient.jobSubmit(
    "report@1.0.0", input, lease, constraints, idempotencyKey, maxRuntimeSec);
```

[`LeaseConstraints.parseStrictUtc`](../../arcp-core/src/main/java/dev/arcp/core/lease/LeaseConstraints.java)
is the wire-format parser; it rejects `+00:00` (must be `Z`).

## Watchdog

When a job's lease has an expiry, the runtime schedules a
`ScheduledExecutorService.schedule(...)` task at `expires_at`
([`SessionLoop.acceptJob`](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L421)).
If the job is still running at the deadline, the watchdog wins the
[`record.transitionTo(JobRecord.Status.ERROR)`](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/JobRecord.java)
CAS and emits `LEASE_EXPIRED`. The agent thread is interrupted but its
competing `CANCELLED` emit is suppressed
([`SessionLoop.runJob` interrupt branch](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java)).

## Example

[`examples/lease-expires-at/`](../../examples/lease-expires-at/) submits a
job with a 1-second `expires_at`, blocks the agent indefinitely, and
asserts the future fails with `LeaseExpiredException`.
