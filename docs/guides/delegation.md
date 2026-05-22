---
title: "Delegation"
sdk: java
spec_sections: ["10"]
kind: guide
since: "1.0.0"
---

# Delegation (§10)

An agent can dispatch work to sub-agents and receive their results. The
runtime enforces that the sub-agent's lease is a **strict subset** of the
parent job's lease.

## Dispatching a sub-agent

```java
(input, ctx) -> {
    // Build a sub-lease — MUST be ⊆ the parent lease
    Lease subLease = Lease.builder()
        .allow("tool.call", "search:*")      // parent has tool.call:search:*
        .allow("net.fetch", "https://api.example.com/**")
        .build();

    // Dispatch and wait for the sub-agent's result
    JobHandle child = ctx.delegate(
        "search-agent@1.0.0",
        input.payload(),
        subLease);

    JobResult childResult = child.result().get();
    return JobOutcome.Success.inline(childResult.result());
}
```

`ctx.delegate` sends `job.submit` on behalf of the parent job, waits for
`job.accepted`, and returns a `JobHandle` scoped to the child job. The
parent job is visible as `DelegateEvent` in the parent's event stream.

## Lease subset enforcement

[`LeaseGuard`](../../arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java)
validates the sub-lease against the parent lease at submit time. If the
sub-lease requests items not covered by the parent, the runtime returns
`LEASE_SUBSET_VIOLATION` (non-retryable) before the child job starts.

Example:

```java
// Parent lease: net.fetch → https://api.example.com/**
// Sub-lease request: net.fetch → https://evil.com/**
//   → LeaseSubsetViolationException immediately
Lease bad = Lease.builder().allow("net.fetch", "https://evil.com/**").build();
ctx.delegate("search-agent@1.0.0", payload, bad); // throws
```

## DelegateEvent

Each `ctx.delegate` call emits a `DelegateEvent` into the parent job's
event stream:

```java
case DelegateEvent d -> System.out.printf(
    "Delegated to %s as job %s%n", d.agentRef(), d.childJobId());
```

## agent.delegate lease

A parent agent must also hold `agent.delegate` in its own lease for the
sub-agent it plans to dispatch:

```java
Lease parentLease = Lease.builder()
    .allow("agent.delegate", "search-agent@*")
    .allow("tool.call", "search:*")
    .allow("net.fetch", "https://api.example.com/**")
    .build();
```

## Nested delegation

Sub-agents can themselves delegate further, provided each level's lease is
a subset of its parent's. The runtime validates every level independently.
There is no fixed depth limit; recursive delegation cycles are broken by
budget exhaustion or timeout.

## Runnable example

[`examples/delegate/`](../../examples/delegate/) — parent agent dispatches
to two sub-agents in parallel, merges results.

[`recipes/multi-agent-budget/`](../../recipes/multi-agent-budget/) — shared
budget ceiling across parallel sub-agents.
