---
title: "arcp (umbrella)"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp` — umbrella artifact

Re-exports `arcp-client` + `arcp-runtime` in a single dependency. Use
this for applications that host a runtime **and** act as a client (e.g.,
parent-process orchestrators, test harnesses, in-process smoke tests).

## Dependency

```kotlin
// Gradle (Kotlin DSL)
implementation("io.agentruntimecontrolprotocol:arcp:1.0.0")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.agentruntimecontrolprotocol</groupId>
  <artifactId>arcp</artifactId>
  <version>1.0.0</version>
</dependency>
```

## What's included

Transitively pulls in:

| Artifact | Purpose |
|---|---|
| `arcp-client` | `ArcpClient`, `JobHandle`, `WebSocketTransport` |
| `arcp-runtime` | `ArcpRuntime`, session FSM, job FSM, `LeaseGuard` |
| `arcp-core` | Wire types, envelope, IDs, Transport SPI |

## When to use

- In-process tests and CI smoke checks (pair with `MemoryTransport`)
- Parent-process orchestrators that submit jobs **and** host a local
  runtime for delegated sub-agents
- Getting-started examples

For production deployments, prefer granular artifacts:
- Client-only: `arcp-client`
- Runtime-only: `arcp-runtime` + one transport adapter
  (`arcp-runtime-jetty`, `arcp-middleware-spring-boot`, etc.)

## Quickstart

See [Getting started](../getting-started.md) for a 60-line in-process
example using this artifact.
