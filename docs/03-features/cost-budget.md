---
title: "Cost budget"
sdk: java
spec_sections: ["9.6"]
order: 8
kind: feature
since: "1.0.0"
---

# Cost budget — §9.6

**Feature flag:** `cost.budget`.

The `cost.budget` lease namespace declares an upper bound on cumulative
cost. Patterns are `currency:decimal` strings. Multiple currencies are
tracked independently.

Cost is reported by the agent via `metric` events whose `name` begins with
`cost.` and whose `unit` matches a budgeted currency.

## Wire shape

```json
"cost.budget": ["USD:5.00", "credits:1000"]

{ "kind": "metric",
  "body": { "name": "cost.inference", "value": 0.0234, "unit": "USD" } }
```

## Java surface

```java
Lease lease = Lease.builder()
    .allow("tool.call", "*")
    .allow("cost.budget", "USD:5.00")
    .build();
```

[`BudgetCounters`](../../arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java)
holds one `AtomicReference<BigDecimal>` per currency. Decrement is a CAS
loop, not a lock; `decrement()` is safe to call concurrently from many
agent virtual threads.

Negative metric values are dropped (spec §9.6 mandate).

## Arithmetic precision

The metric value rides as a JSON number. The wire mapper
([`ArcpMapper`](../../arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java))
sets `USE_BIG_DECIMAL_FOR_FLOATS=true` so the parsed value is an exact
`BigDecimal` — `0.1 + 0.2` is `0.3`, not `0.30000000000000004`.

## Enforcement

`JobContext.authorize(...)` calls `BudgetCounters.ensureAllPositive()`,
which throws `BudgetExhaustedException` if any currency has reached zero.
The runtime surfaces this as `job.error { code: BUDGET_EXHAUSTED,
retryable: false }`. Naive retry will fail identically.

## Example

[`examples/cost-budget/`](../../examples/cost-budget/) declares a $5
budget, has the agent emit $0.75 metrics until exhaustion, and asserts the
result future fails with `BudgetExhaustedException`.
