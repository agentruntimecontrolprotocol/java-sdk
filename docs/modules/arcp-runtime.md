---
title: "arcp-runtime"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-runtime`

Runtime-side SDK. Host agents, enforce leases, manage sessions.

## Dependency

```kotlin
implementation("dev.arcp:arcp-runtime:1.0.0")
```

## Key classes

### `ArcpRuntime`

Main entry point. Implements `AutoCloseable`.

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .agent("my-agent", "1.0.0", handler)
    .agent("my-agent", "2.0.0", handlerV2)
    .verifier(BearerVerifier.staticToken("token", principal))
    .heartbeatIntervalSec(30)
    .resumeWindowSec(60)
    .credentialProvisioner(provisioner)
    .credentialRevocationStore(store)
    .mapper(customMapper)
    .build();

runtime.accept(transport);   // accept one connection
runtime.close();             // shut down scheduler, close all sessions
```

### `AgentRegistry`

Manages agent registrations:

```java
runtime.agents().register("my-agent", "3.0.0", handlerV3);
runtime.agents().setDefault("my-agent", "2.0.0");
runtime.agents().versions("my-agent"); // → ["1.0.0", "2.0.0", "3.0.0"]
```

### `LeaseGuard`

Enforces lease authorization:

```java
// Inject into agent handler via JobContext:
ctx.authorize("net.fetch", "https://api.example.com/data");
ctx.authorize("model.use", "claude-3-5-sonnet-20241022");
```

`LeaseGuard` matches globs (`*` = no `/`, `**` = any), checks
`expires_at`, and throws `PermissionDeniedException` on miss.

### `BudgetCounters`

Thread-safe budget tracking using `AtomicReference<BigDecimal>` CAS:

```java
// Driven internally by MetricEvent emissions from the agent.
// Throws BudgetExhaustedException when the total exceeds the cap.
```

### `ResumeBuffer`

In-memory event buffer for session resume:

```java
// Configured via:
ArcpRuntime.builder().resumeWindowSec(300);
```

Holds `job.event`, `job.result`, `job.error` frames per session for the
configured TTL. Eviction is time-based, not size-based.

### `JobRecord`

Tracks job state; terminal state is set via CAS on a `Status` enum:

```
PENDING → RUNNING → { SUCCESS | ERROR | CANCELLED | TIMED_OUT }
```

### `BearerVerifier` SPI

```java
@FunctionalInterface
public interface BearerVerifier {
    Principal verify(String token) throws UnauthenticatedException;
}
```

## Threading model

- **Virtual threads** (JEP 444): every per-job worker and every transport
  dispatch.
- **One `ScheduledExecutorService`** (platform threads): heartbeat ticks
  and lease-expiry watchdogs.
- No `StructuredTaskScope` — preview in JDK 21, different shape in JDK 25;
  SDK targets `--release 21`.

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.runtime` | `ArcpRuntime` |
| `dev.arcp.runtime.agent` | `AgentRegistry`, `AgentHandler` |
| `dev.arcp.runtime.session` | Session FSM, `ResumeBuffer`, `JobRecord`, `SessionLoop` |
| `dev.arcp.runtime.lease` | `LeaseGuard`, `BudgetCounters` |
| `dev.arcp.runtime.credentials` | `CredentialProvisioner` SPI, `FileCredentialRevocationStore` |
