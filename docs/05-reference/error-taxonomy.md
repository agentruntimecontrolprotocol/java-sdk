---
title: "Error taxonomy"
sdk: java
spec_sections: ["12"]
order: 2
kind: reference
since: "1.0.0"
---

# Error taxonomy

Fifteen codes, six retryable, nine non-retryable, all surfaced as sealed
[`ArcpException`](../../arcp-core/src/main/java/dev/arcp/core/error/ArcpException.java)
subclasses. The sealed boundary forces a binary classification: a generic
retry helper that catches `RetryableArcpException` cannot accidentally
retry a non-retryable code.

| `ErrorCode` | Retryable | Java exception | Spec § | Typical trigger |
|---|---|---|---|---|
| `PERMISSION_DENIED` | no | `PermissionDeniedException` | §9.3 | `LeaseGuard.authorize` mismatch |
| `LEASE_SUBSET_VIOLATION` | no | `LeaseSubsetViolationException` | §9.4 | Delegated lease exceeds parent |
| `JOB_NOT_FOUND` | no | `JobNotFoundException` | §7 | `subscribe(jobId)` or `cancel(jobId)` for an unknown id |
| `DUPLICATE_KEY` | no | `DuplicateKeyException` | §7.2 | `idempotency_key` reuse with conflicting payload |
| `AGENT_NOT_AVAILABLE` | no | `AgentNotAvailableException` | §7.5 | Bare-name resolve against an empty registry |
| `AGENT_VERSION_NOT_AVAILABLE` | no | `AgentVersionNotAvailableException` | §7.5 | `name@version` not registered |
| `CANCELLED` | no | `CancelledException` | §7.4 | `job.cancel` or `session.close` while running |
| `TIMEOUT` | yes | `TimeoutException` | §7.3 | `max_runtime_sec` exceeded |
| `RESUME_WINDOW_EXPIRED` | no | `ResumeWindowExpiredException` | §6.3 | Resume after `resume_window_sec` |
| `HEARTBEAT_LOST` | yes | `HeartbeatLostException` | §6.4 | Two missed heartbeat intervals |
| `LEASE_EXPIRED` | no | `LeaseExpiredException` | §9.5 | Lease watchdog fired |
| `BUDGET_EXHAUSTED` | no | `BudgetExhaustedException` | §9.6 | `BudgetCounters.ensureAllPositive` |
| `INVALID_REQUEST` | no | `InvalidRequestException` | §5 | Malformed envelope, past `expires_at` |
| `UNAUTHENTICATED` | no | `UnauthenticatedException` | §6.1 | `BearerVerifier.verify` rejected |
| `INTERNAL_ERROR` | yes | `InternalErrorException` | §12 | Unhandled `Throwable` in agent |

## Decoding

`ArcpException.from(ErrorPayload)` is a factory: `switch` on the
`ErrorCode` enum and return the concrete subclass. The `retryable` bit on
the wire is honoured if present; otherwise the `ErrorCode` default
applies.

## Retry helpers

Because the hierarchy is sealed, a generic helper can be written safely:

```java
static <T> T retryUntil(Callable<T> op, int maxAttempts) throws Exception {
    Exception last = null;
    for (int i = 0; i < maxAttempts; i++) {
        try {
            return op.call();
        } catch (RetryableArcpException e) {
            last = e;
            Thread.sleep(100L * (1 << i));
        }
        // NonRetryableArcpException is NOT caught: propagates immediately.
    }
    throw last;
}
```

`LEASE_EXPIRED` and `BUDGET_EXHAUSTED` will not be silently retried — the
compiler enforces it.
