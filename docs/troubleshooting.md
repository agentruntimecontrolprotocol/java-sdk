---
title: "Troubleshooting"
sdk: java
spec_sections: ["12"]
kind: reference
since: "1.0.0"
---

# Troubleshooting

## Error code reference

All 15 canonical error codes, their likely causes, and fixes.

### `PERMISSION_DENIED`

**Exception**: `PermissionDeniedException` (non-retryable)

**Causes**
- Agent called `ctx.authorize(namespace, pattern)` and the pattern is not
  covered by the job's lease.
- `model.use` lease missing for the model the agent tried to use.

**Fixes**
- Expand the lease in `job.submit` to include the required namespace and
  glob.
- Use `**` glob for broad access during development; tighten in production.

---

### `LEASE_SUBSET_VIOLATION`

**Exception**: `LeaseSubsetViolationException` (non-retryable)

**Causes**
- A delegating agent tried to grant a sub-agent lease items not covered by
  its own lease (§10).

**Fixes**
- Ensure the parent job's lease covers every item you pass to
  `ctx.delegate(subAgent, subLease)`.

---

### `JOB_NOT_FOUND`

**Exception**: `JobNotFoundException` (non-retryable)

**Causes**
- `job.subscribe` or `job.cancel` sent with an unknown or expired job ID.
- The runtime restarted and lost in-memory job state.

**Fixes**
- Check the job ID. If the runtime restarted, resubmit the job (use the
  same `idempotency_key` to avoid duplicate execution).

---

### `DUPLICATE_KEY`

**Exception**: `DuplicateKeyException` (non-retryable)

**Causes**
- A `job.submit` arrived with an `idempotency_key` that is already
  associated with a **different** payload.

**Fixes**
- Reuse the same key only for exactly the same submit payload.
- To force a fresh run, change or omit the key.

---

### `AGENT_NOT_AVAILABLE`

**Exception**: `AgentNotAvailableException` (non-retryable)

**Causes**
- The agent name in `job.submit` is not registered with the runtime.

**Fixes**
- Check for typos in the agent name.
- Verify `ArcpRuntime.builder().agent(name, version, handler)` was called.

---

### `AGENT_VERSION_NOT_AVAILABLE`

**Exception**: `AgentVersionNotAvailableException` (non-retryable)

**Causes**
- `name@version` specified but that version is not registered.

**Fixes**
- List available versions via `session.welcome.agents`.
- Register the version: `runtime.agents().register(name, version, handler)`.

---

### `CANCELLED`

**Exception**: `CancelledException` (non-retryable)

**Causes**
- `handle.cancel()` was called and the runtime confirmed cancellation.

**Fixes**
- Expected behaviour. Do not retry a cancelled job unless the business
  logic requires it.

---

### `TIMEOUT`

**Exception**: `TimeoutException` (retryable)

**Causes**
- The job ran past the `timeout_ms` set in `job.submit`.
- The lease's `expires_at` expired while the job was running.

**Fixes**
- Increase `timeout_ms` in `job.submit`.
- Check that `lease_expires_at` is set far enough in the future.
- See [guides/jobs.md](guides/jobs.md#timeouts).

---

### `RESUME_WINDOW_EXPIRED`

**Exception**: `ResumeWindowExpiredException` (non-retryable)

**Causes**
- A `session.hello` with `resume_token` arrived but the runtime evicted the
  buffer (default window: 60 s; configurable via
  `ArcpRuntime.builder().resumeWindowSec(int)`).

**Fixes**
- Reconnect faster — reconnect within the window.
- Increase the resume window if your network can partition for longer:
  `resumeWindowSec(300)`.
- If the window has passed, start a fresh session and re-subscribe to
  running jobs by ID.

---

### `HEARTBEAT_LOST`

**Exception**: `HeartbeatLostException` (retryable)

**Causes**
- The client missed two consecutive heartbeat intervals without responding
  with `session.pong`.

**Fixes**
- Resume the session (`session.hello` with `resume_token` +
  `last_event_seq`).
- If heartbeats are too aggressive, increase the interval:
  `heartbeatIntervalSec(60)` in `ArcpRuntime.Builder`.
- Check for blocking calls on the transport receive thread.

---

### `LEASE_EXPIRED`

**Exception**: `LeaseExpiredException` (non-retryable)

**Causes**
- The job's `lease_expires_at` passed while the job was still running.

**Fixes**
- Set `lease_expires_at` to a point far enough in the future.
- If the job is long-running, omit `lease_expires_at` entirely (the lease
  then expires only when the job terminates).

---

### `BUDGET_EXHAUSTED`

**Exception**: `BudgetExhaustedException` (non-retryable)

**Causes**
- The agent emitted metric events whose `cost.budget` total exceeded the
  limit set in the lease.

**Fixes**
- Raise the budget in the lease: `cost.budget → "USD:10.00"`.
- Optimize the agent to emit fewer or smaller metric events.
- See [guides/leases.md](guides/leases.md#cost-budget).

---

### `INVALID_REQUEST`

**Exception**: `InvalidRequestException` (non-retryable)

**Causes**
- Malformed envelope (missing required field, wrong type).
- `lease_expires_at` is in the past or uses a UTC offset (`+00:00`) instead
  of `Z`.
- Feature used that was not negotiated in `session.welcome`.

**Fixes**
- Use the SDK builders rather than hand-rolling JSON.
- Always format `lease_expires_at` with `Z` suffix (e.g.,
  `"2024-09-01T00:00:00Z"`).
- Check that both peers have negotiated the feature before using it.

---

### `UNAUTHENTICATED`

**Exception**: `UnauthenticatedException` (non-retryable)

**Causes**
- `session.hello` did not carry a bearer token.
- The token was rejected by the runtime's `BearerVerifier`.

**Fixes**
- Set `ArcpClient.Builder.bearer(token)`.
- Check that the token matches the verifier: `BearerVerifier.staticToken(...)`
  or your custom SPI implementation.
- See [guides/auth.md](guides/auth.md).

---

### `INTERNAL_ERROR`

**Exception**: `InternalErrorException` (retryable)

**Causes**
- Unhandled exception inside the runtime or agent.

**Fixes**
- Check runtime logs (SLF4J — configure a binding such as logback).
- Agent code that throws unchecked exceptions surfaces as `INTERNAL_ERROR`;
  return `JobOutcome.Failure` explicitly instead.

---

## Retry helper pattern

```java
// See guides/errors.md for the full pattern.
for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    try {
        return client.submit(submit).result().get();
    } catch (ExecutionException ex) {
        if (ex.getCause() instanceof RetryableArcpException && attempt < MAX_RETRIES) {
            Thread.sleep(backoff(attempt));
            continue;
        }
        throw ex;
    }
}
```

`RetryableArcpException` is the base class for `TimeoutException`,
`HeartbeatLostException`, and `InternalErrorException`. All others extend
`NonRetryableArcpException`. See [guides/errors.md](guides/errors.md).

## Common build issues

### `Unable to locate a Java Runtime`

Set `JAVA_HOME` to a JDK 21+ installation. The Gradle wrapper does not
bundle a JDK.

### Spotless fails on CI

Spotless runs only on JDK 21 (see `.github/workflows/ci.yml`). Run
`./gradlew spotlessApply` locally to reformat before pushing.

### `module-info.class` not found

Ensure `--release 21` is set in `build.gradle.kts`. The SDK targets JDK 21
bytecode even when built on JDK 25.
