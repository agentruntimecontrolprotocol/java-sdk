---
title: "Leases"
sdk: java
spec_sections: ["9", "9.2", "9.5", "9.6", "9.7"]
kind: guide
since: "1.0.0"
---

# Leases (§9)

A `Lease` is a bag of namespace → glob-pattern lists that bounds what an
agent is allowed to do during a job.

## Reserved namespaces

| Namespace | Meaning | Spec |
|---|---|---|
| `fs.read` | Filesystem path globs for reading | §9.2 |
| `fs.write` | Filesystem path globs for writing | §9.2 |
| `net.fetch` | Outbound URL globs | §9.2 |
| `tool.call` | Tool-name globs | §9.2 |
| `agent.delegate` | Sub-agent name globs | §9.2 |
| `cost.budget` | `currency:decimal` upper bounds | §9.6 |
| `model.use` | Model-ID globs (v1.1) | §9.7 |

Custom namespaces (vendor extensions) are allowed; they are enforced
identically by `LeaseGuard`.

## Building a lease

```java
Lease lease = Lease.builder()
    .allow("fs.read",  "/data/**")
    .allow("fs.write", "/tmp/**")
    .allow("net.fetch", "https://api.example.com/**")
    .allow("tool.call", "search:*")
    .allow("agent.delegate", "summarise@*")
    .build();

client.submit(jobSubmit("my-agent", payload).lease(lease).build());
```

## Authorizing operations

Inside the agent:

```java
(input, ctx) -> {
    ctx.authorize("net.fetch", "https://api.example.com/data"); // passes
    ctx.authorize("net.fetch", "https://evil.com/");             // PermissionDeniedException
    ctx.authorize("fs.write", "/tmp/output.json");               // passes
    ctx.authorize("model.use", "claude-3-5-sonnet-20241022");   // passes if allowed
    return JobOutcome.Success.inline(result);
}
```

`ctx.authorize` throws `PermissionDeniedException` (non-retryable) on
miss.

## Glob semantics

[`LeaseGuard`](../../arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java)
matches patterns with:

| Pattern | Matches |
|---|---|
| `*` | Any characters except `/` |
| `**` | Any characters including `/` |

Examples:
- `"https://api.example.com/**"` — any path under that origin
- `"tool:*"` — any single-segment tool name starting with `tool:`
- `"/tmp/**"` — any path under `/tmp/`

## Expiration (§9.5)

Set a wall-clock deadline on the lease:

```java
Lease lease = Lease.builder()
    .allow("net.fetch", "**")
    .expiresAt(Instant.now().plus(Duration.ofHours(1)))
    .build();
```

The runtime schedules a watchdog via `ScheduledExecutorService.schedule`
that fires `LEASE_EXPIRED` (non-retryable) at `expires_at`. Jobs running
past the deadline are terminated.

Formatting rules:
- Serialize as ISO-8601 with `Z` suffix: `"2024-09-01T00:00:00Z"`.
- A UTC offset (`+00:00`) is rejected with `INVALID_REQUEST`.
- A past `expires_at` is rejected immediately.

```java
// LeaseConstraints.of wraps an Instant; toString() always uses Z.
LeaseConstraints c = LeaseConstraints.of(Instant.now().plusSeconds(3600));
```

## Cost budget (§9.6)

Cap the agent's spend using `cost.budget`:

```java
Lease lease = Lease.builder()
    .budget("USD", new BigDecimal("10.00"))
    .build();
```

Agents emit `MetricEvent` records that drive
[`BudgetCounters`](../../arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java).
`BudgetCounters` uses an `AtomicReference<BigDecimal>` CAS loop so budget
checks are thread-safe without locks.

When the cumulative cost exceeds the cap, the runtime emits `job.error`
with `BUDGET_EXHAUSTED` (non-retryable).

**Why `USE_BIG_DECIMAL_FOR_FLOATS`?** Jackson's default parses JSON
numbers as `double`, which causes rounding errors in decimal arithmetic.
`ArcpMapper` enables `USE_BIG_DECIMAL_FOR_FLOATS` so budget values are
exact.

## Model-use lease (§9.7)

Since v1.1. Gate which model IDs an agent may use:

```java
Lease lease = Lease.builder()
    .allow("model.use", "claude-*")
    .allow("model.use", "gpt-4*")
    .build();
```

Inside the agent:

```java
ctx.authorize("model.use", "claude-3-5-sonnet-20241022"); // passes
ctx.authorize("model.use", "llama3");                      // PermissionDeniedException
```

The `LeaseGuard.authorizeModel(modelId)` helper is a typed alias for
`authorize("model.use", modelId)`.

## Runnable examples

| Example | What it shows |
|---|---|
| [`examples/cost-budget/`](../../examples/cost-budget/) | Budget cap + `BUDGET_EXHAUSTED` |
| [`examples/lease-expires-at/`](../../examples/lease-expires-at/) | Wall-clock expiry |
| [`examples/lease-violation/`](../../examples/lease-violation/) | `PermissionDeniedException` path |
| [`recipes/email-vendor-leases/`](../../recipes/email-vendor-leases/) | Custom namespace |
