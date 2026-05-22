---
title: "Model use"
sdk: java
spec_sections: ["9.7"]
order: 9
kind: feature
since: "1.1.0"
---

# Model use — §9.7

**Feature flag:** `model.use`.

The `model.use` lease namespace constrains model IDs exactly like any other
lease namespace. Agents call `JobContext.authorize("model.use", modelId)` or
the convenience `LeaseGuard.authorizeModel(modelId)` before an LLM invocation
that the runtime can observe.

## Wire shape

```json
"model.use": ["tier-fast/*", "anthropic/claude-3-haiku-20240307"]
```

## Java surface

```java
Lease lease = Lease.builder()
    .allow("model.use", "tier-fast/*")
    .build();
```

[`LeaseGuard`](../../arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java)
uses the same glob matcher as `tool.call` and `fs.read`. A miss raises
`PermissionDeniedException`, which the runtime surfaces as `PERMISSION_DENIED`.

## Delegation and subsetting

[`Lease.contains`](../../arcp-core/src/main/java/dev/arcp/core/lease/Lease.java)
covers `model.use` as part of the generic lease bag. A child lease may narrow
`tier-fast/*` to `tier-fast/sonnet`, but it may not add `tier-slow/*`.
