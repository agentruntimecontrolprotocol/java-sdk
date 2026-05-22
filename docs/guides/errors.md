---
title: "Errors"
sdk: java
spec_sections: ["12"]
kind: guide
since: "1.0.0"
---

# Errors (§12)

Fifteen canonical error codes, a sealed exception hierarchy, and a retry
helper pattern.

## Exception hierarchy

```
ArcpException
├── RetryableArcpException       ← safe to retry
│   ├── TimeoutException
│   ├── HeartbeatLostException
│   └── InternalErrorException
└── NonRetryableArcpException    ← never retry automatically
    ├── PermissionDeniedException
    ├── LeaseSubsetViolationException
    ├── JobNotFoundException
    ├── DuplicateKeyException
    ├── AgentNotAvailableException
    ├── AgentVersionNotAvailableException
    ├── CancelledException
    ├── ResumeWindowExpiredException
    ├── LeaseExpiredException
    ├── BudgetExhaustedException
    ├── InvalidRequestException
    └── UnauthenticatedException
```

The split at `RetryableArcpException` / `NonRetryableArcpException` means
a generic retry loop can never accidentally retry `LEASE_EXPIRED` or
`BUDGET_EXHAUSTED`.

See [`ArcpException`](../../arcp-core/src/main/java/dev/arcp/core/error/ArcpException.java)
and [`ErrorCode`](../../arcp-core/src/main/java/dev/arcp/core/error/ErrorCode.java).

## Full error table

| Code | Retryable | Exception | Spec § | Typical trigger |
|---|---|---|---|---|
| `PERMISSION_DENIED` | no | `PermissionDeniedException` | §9 | Pattern not covered by lease |
| `LEASE_SUBSET_VIOLATION` | no | `LeaseSubsetViolationException` | §10 | Sub-lease exceeds parent lease |
| `JOB_NOT_FOUND` | no | `JobNotFoundException` | §7 | Unknown or expired job ID |
| `DUPLICATE_KEY` | no | `DuplicateKeyException` | §7.1 | Same key, different payload |
| `AGENT_NOT_AVAILABLE` | no | `AgentNotAvailableException` | §7.5 | Agent name not registered |
| `AGENT_VERSION_NOT_AVAILABLE` | no | `AgentVersionNotAvailableException` | §7.5 | Version not registered |
| `CANCELLED` | no | `CancelledException` | §7.3 | `handle.cancel()` called |
| `TIMEOUT` | yes | `TimeoutException` | §7.4 | Job ran past `timeout_ms` |
| `RESUME_WINDOW_EXPIRED` | no | `ResumeWindowExpiredException` | §6.3 | Reconnected after buffer TTL |
| `HEARTBEAT_LOST` | yes | `HeartbeatLostException` | §6.4 | Two missed ping intervals |
| `LEASE_EXPIRED` | no | `LeaseExpiredException` | §9.5 | `lease_expires_at` passed |
| `BUDGET_EXHAUSTED` | no | `BudgetExhaustedException` | §9.6 | `cost.budget` exceeded |
| `INVALID_REQUEST` | no | `InvalidRequestException` | §5 | Malformed envelope / bad datetime |
| `UNAUTHENTICATED` | no | `UnauthenticatedException` | §6.1 | Missing or invalid bearer token |
| `INTERNAL_ERROR` | yes | `InternalErrorException` | §12 | Unhandled runtime exception |

## Retry helper pattern

```java
static final int MAX_RETRIES = 3;

JobResult submit(ArcpClient client, JobSubmit req) throws Exception {
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        try {
            return client.submit(req).result().get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RetryableArcpException && attempt < MAX_RETRIES) {
                long delayMs = Duration.ofSeconds(1L << attempt).toMillis();
                Thread.sleep(delayMs);
                // For HeartbeatLostException: reconnect the session first
                if (cause instanceof HeartbeatLostException) reconnect(client);
                continue;
            }
            throw ex;
        }
    }
    throw new IllegalStateException("unreachable");
}
```

For `HeartbeatLostException`, reconnect the session before retrying
(resume with the saved `resume_token` + `last_event_seq`). For
`TimeoutException` and `InternalErrorException`, a simple sleep + retry is
sufficient.

## Accessing the error code

```java
} catch (ExecutionException ex) {
    if (ex.getCause() instanceof ArcpException e) {
        System.out.println(e.errorCode()); // TIMEOUT, BUDGET_EXHAUSTED, etc.
        System.out.println(e.message());   // human-readable from job.error
    }
}
```

## Troubleshooting

Causes and fixes for each error code: [troubleshooting.md](../troubleshooting.md).
