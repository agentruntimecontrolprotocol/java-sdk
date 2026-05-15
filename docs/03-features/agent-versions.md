---
title: "Agent versioning"
sdk: java
spec_sections: ["7.5"]
order: 9
kind: feature
since: "1.0.0"
---

# Agent versioning — §7.5

**Feature flag:** `agent_versions`.

The `agent` field of `job.submit` accepts `name` or `name@version`. The
runtime advertises an `agents` capability listing names, available
versions, and a `default` per name. A bare name resolves to the default; a
pinned `name@version` requires an exact match.

Once a job is accepted, its agent version is fixed. The runtime MUST NOT
migrate a running job to a different version.

## Wire shape

```json
"capabilities": {
  "agents": [
    { "name": "code-refactor",
      "versions": ["1.0.0", "2.0.0"], "default": "2.0.0" }
  ]
}

"payload": { "agent": "code-refactor@2.0.0", "input": {…} }
```

## Java surface

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .agent("code-refactor", "1.0.0", v1)
    .agent("code-refactor", "2.0.0", v2)
    .build();
runtime.agents().setDefault("code-refactor", "2.0.0");
```

The grammar parser is [`AgentRef.parse`](../../arcp-core/src/main/java/dev/arcp/core/agents/AgentRef.java);
resolution is [`AgentRegistry.resolve`](../../arcp-runtime/src/main/java/dev/arcp/runtime/agent/AgentRegistry.java).

## Errors

| Wire scenario | Java exception |
|---|---|
| Bare name, no registration | `AgentNotAvailableException` |
| `name@version`, version missing | `AgentVersionNotAvailableException` |

Both are non-retryable; the client receives them synchronously from
`client.submit(...)`.

## Example

[`examples/agent-versions/`](../../examples/agent-versions/) registers two
versions, asserts bare-name → default + pinned-name → exact, and that an
unknown version surfaces `AgentVersionNotAvailableException`.
