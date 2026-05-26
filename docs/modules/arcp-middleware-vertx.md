---
title: "arcp-middleware-vertx"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-middleware-vertx`

Vert.x 4 WebSocket handler adapter. Bridges `ArcpRuntime` into a Vert.x HTTP
server's `webSocketHandler`.

## Dependency

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-middleware-vertx:1.0.0")
```

`io.vertx:vertx-core` is `compileOnly` — the Vert.x application provides it.

## Key classes

### `ArcpVertxHandler`

Implements `Handler<ServerWebSocket>`. Validates path and optional host
allowlist, then adapts each accepted socket to a `VertxWebSocketTransport` and
calls `runtime.accept(transport)`.

```java
ArcpVertxHandler handler = ArcpVertxHandler.builder()
    .runtime(runtime)
    .path("/arcp")                              // default: /arcp
    .allowedHosts(List.of("agents.example.com")) // optional
    .build();

// Register with a Vert.x HttpServer:
httpServer.webSocketHandler(handler);
```

Sockets at paths other than the configured path are rejected with close code
`1008 (Policy Violation)`. Sockets with a disallowed `Host` header are also
rejected with `1008`.

### `VertxWebSocketTransport`

Adapts a Vert.x `ServerWebSocket` to the `Transport` SPI. Delivers inbound
text frames to an internal `SubmissionPublisher<Envelope>`; outbound frames are
sent via `ws.writeTextMessage(...)`. Created per accepted WebSocket connection.

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.middleware.vertx` | `ArcpVertxHandler`, `VertxWebSocketTransport` |

## Example — minimal Vert.x HTTP server

```java
Vertx vertx = Vertx.vertx();

ArcpRuntime runtime = ArcpRuntime.builder()
    .agent("my-agent", "1.0.0", handler)
    .verifier(BearerVerifier.staticToken("secret", principal))
    .build();

ArcpVertxHandler arcpHandler = ArcpVertxHandler.builder()
    .runtime(runtime)
    .path("/arcp")
    .build();

vertx.createHttpServer()
    .webSocketHandler(arcpHandler)
    .listen(8080)
    .onSuccess(s -> System.out.println("Listening on " + s.actualPort()));
```

## Notes

- Vert.x event loop threads are **not** blocked; inbound frame handling
  dispatches to the runtime on a virtual thread via `runtime.accept(transport)`.
- `allowedHosts` is checked on the Vert.x event loop before the transport is
  handed to the runtime, so it adds no contention on the runtime thread pool.

## When to use

Use `arcp-middleware-vertx` when your application is built on Vert.x 4. For
other environments see [`arcp-runtime-jetty`](arcp-runtime-jetty.md),
[`arcp-middleware-jakarta`](arcp-middleware-jakarta.md), or
[`arcp-middleware-spring-boot`](arcp-middleware-spring-boot.md).
