---
title: "Jobs"
sdk: java
spec_sections: ["7", "7.1", "7.3", "7.4", "7.5", "7.6"]
kind: guide
since: "1.0.0"
---

# Jobs (§7)

A job is one agent invocation. State machine:

```
PENDING → RUNNING → { SUCCESS | ERROR | CANCELLED | TIMED_OUT }
```

Terminal states are sticky — competing transitions lose to the first
writer via CAS on
[`JobRecord.Status`](../../arcp-runtime/src/main/java/dev/arcp/runtime/session/JobRecord.java).

## Submit (§7.1)

```java
JobHandle handle = client.submit(
    ArcpClient.jobSubmit("echo@1.0.0",
        JsonNodeFactory.instance.objectNode().put("input", "hello"))
    .idempotencyKey("req-42")   // optional — idempotent resubmit
    .timeoutMs(30_000L)         // optional — job-level timeout
    .lease(myLease)             // optional — see guides/leases.md
    .build());
```

`client.submit(...)` blocks until `job.accepted` is received. The returned
`JobHandle` carries:
- `handle.jobId()` — the runtime-assigned `JobId`
- `handle.agentRef()` — the resolved `name@version`
- `handle.events()` — `Flow.Publisher<EventBody>` (buffered, replaying)
- `handle.result()` — `CompletableFuture<JobResult>`

## Idempotency

Re-submitting the same `idempotency_key` with the **same** payload returns
the same accepted job (no second execution). With a **different** payload,
the runtime returns `DUPLICATE_KEY` (non-retryable — programming error).

```java
String key = UUID.randomUUID().toString();
// First submit: runs the agent
JobHandle h1 = client.submit(jobSubmit("my-agent", p).idempotencyKey(key).build());
// Second submit with the same key and payload: returns h1's job, no second run
JobHandle h2 = client.submit(jobSubmit("my-agent", p).idempotencyKey(key).build());
assert h1.jobId().equals(h2.jobId());
```

## Awaiting the result

```java
// Blocking:
JobResult result = handle.result().get();
System.out.println(result.result()); // the agent's JSON output

// Non-blocking:
handle.result().thenAccept(r -> System.out.println(r.result()));
```

`handle.result()` fails with the matching `ArcpException` subclass when
`job.error` arrives (e.g., `TimeoutException`, `CancelledException`).

## Cancel (§7.3)

```java
handle.cancel();    // sends job.cancel; returns immediately
// handle.result() will complete with CancelledException
```

The runtime interrupts the worker virtual thread. Agents check
`ctx.cancelled()` cooperatively:

```java
(input, ctx) -> {
    for (Item item : items) {
        if (ctx.cancelled()) break;
        // ... process item ...
    }
    return JobOutcome.Success.inline(results);
}
```

## Timeouts (§7.4)

Per-job timeout in milliseconds:

```java
ArcpClient.jobSubmit("slow-agent", payload)
    .timeoutMs(60_000L)
    .build();
```

On timeout, the runtime emits `job.error` with `TIMEOUT`
(`TimeoutException` — retryable). The job transitions to `TIMED_OUT`.

Lease expiration (`lease_expires_at`) can also cause premature termination;
see [guides/leases.md](leases.md#expiration).

## Agent versioning (§7.5)

The `agent` field accepts `name` or `name@version`:

```java
client.submit(jobSubmit("code-refactor", payload));          // default version
client.submit(jobSubmit("code-refactor@2.0.0", payload));    // exact version
```

Available versions are advertised in `session.welcome.agents`. A bare name
resolves to the registered default:

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .agent("code-refactor", "1.0.0", v1)
    .agent("code-refactor", "2.0.0", v2)
    .build();
runtime.agents().setDefault("code-refactor", "2.0.0");
```

`AGENT_NOT_AVAILABLE` / `AGENT_VERSION_NOT_AVAILABLE` on miss (both
non-retryable).

See [`AgentRef.parse`](../../arcp-core/src/main/java/dev/arcp/core/agents/AgentRef.java)
and [`AgentRegistry.resolve`](../../arcp-runtime/src/main/java/dev/arcp/runtime/agent/AgentRegistry.java).

## Subscribe / unsubscribe (§7.6)

Attach a second observer to a job that is already running:

```java
// Subscribe from the beginning of known history:
JobHandle observer = client.subscribe(
    jobId, SubscribeOptions.withHistory(0L));

// Subscribe to new events only (no replay):
JobHandle observer = client.subscribe(
    jobId, SubscribeOptions.live());
```

`job.subscribed` is returned by the runtime confirming the subscription.

Constraints:
- Subscribers can only **observe** — they cannot cancel the job.
- Subscribe is restricted to jobs owned by the calling principal.

## List jobs (§6.6)

```java
Page<JobSummary> page = client.listJobs(JobFilter.all());
while (page.hasNext()) {
    page.items().forEach(s ->
        System.out.printf("%s %s%n", s.jobId(), s.status()));
    page = page.next();
}
```

Filter by status:

```java
JobFilter filter = JobFilter.builder()
    .status(JobStatus.RUNNING)
    .build();
```

## Cost budget (§9.6)

Agents emit `MetricEvent` records that drive budget accounting. Cap total
spend in the lease:

```java
Lease lease = Lease.builder()
    .budget("USD", new BigDecimal("5.00"))
    .build();

client.submit(jobSubmit("my-agent", payload).lease(lease).build());
```

When the budget is exhausted, the runtime emits `job.error` with
`BUDGET_EXHAUSTED` (non-retryable). See [guides/leases.md](leases.md#cost-budget).

## Runnable examples

| Example | What it shows |
|---|---|
| [`examples/submit-and-stream/`](../../examples/submit-and-stream/) | Submit, stream events, await result |
| [`examples/cancel/`](../../examples/cancel/) | Cancel mid-execution |
| [`examples/agent-versions/`](../../examples/agent-versions/) | Multi-version dispatch |
| [`examples/list-jobs/`](../../examples/list-jobs/) | Paginated job listing |
| [`examples/subscribe/`](../../examples/subscribe/) | Observer subscription |
| [`examples/cost-budget/`](../../examples/cost-budget/) | Budget enforcement |
| [`examples/idempotent-retry/`](../../examples/idempotent-retry/) | Idempotency key |
