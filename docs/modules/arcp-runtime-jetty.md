---
title: "arcp-runtime-jetty"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-runtime-jetty`

Embedded Jetty 12 transport adapter. Expose an `ArcpRuntime` over WebSocket with
a single dependency and three lines of code.

## Dependency

```kotlin
implementation("dev.arcp:arcp-runtime-jetty:1.0.0")
```

## Key classes

### `ArcpJettyServer`

Embedded Jetty 12 server that binds an `ArcpRuntime` to a WebSocket endpoint.
Implements `AutoCloseable`.

```java
ArcpJettyServer server = ArcpJettyServer.builder(runtime)
    .port(8080)          // 0 = ephemeral (useful in tests)
    .path("/arcp")       // default: /arcp
    .allowedHosts(List.of("agents.example.com"))
    .build()
    .start();

URI uri = server.uri();  // e.g. ws://127.0.0.1:8080/arcp
int port = server.port(); // resolved ephemeral port

server.close(); // stops the Jetty server
```

**Thread pool**: `VirtualThreadPool` — every Jetty request runs on a virtual
thread, which aligns with the ARCP SDK's virtual-thread model.

### `ArcpJettyEndpoint`

Jakarta WebSocket `@ServerEndpoint` used internally. Not intended for direct use;
the `ArcpJettyServer` builder configures it automatically via
`JakartaWebSocketServletContainerInitializer`.

### `WebSocketJsonTransport`

Adapts a Jakarta `Session` to the `Transport` SPI. Also used internally.

## Transitive dependencies

| Dependency | Version |
|---|---|
| `arcp-runtime` | same version |
| `arcp-core` | same version |
| `org.eclipse.jetty:jetty-server` | 12.x |
| `org.eclipse.jetty.ee10:jetty-ee10-servlet` | 12.x |
| `org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jakarta-server` | 12.x |

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.runtime.jetty` | `ArcpJettyServer`, `ArcpJettyEndpoint`, `WebSocketJsonTransport` |

## Example

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .agent("echo", "1.0.0", (input, ctx) ->
        JobOutcome.Success.inline(input.payload()))
    .verifier(BearerVerifier.staticToken("secret", principal))
    .build();

try (ArcpJettyServer server = ArcpJettyServer.builder(runtime)
        .port(9000)
        .build()
        .start()) {

    System.out.println("Listening on " + server.uri());
    Thread.sleep(Long.MAX_VALUE); // run until interrupted
}
```

## When to use

Use `arcp-runtime-jetty` when:

- You need an embedded server with no external Servlet container
- You're writing integration or end-to-end tests with an ephemeral port (`port(0)`)
- You want the simplest possible path from `ArcpRuntime` to a WebSocket endpoint

For production deployments in an existing Servlet container (Tomcat, WildFly,
etc.), prefer [`arcp-middleware-jakarta`](arcp-middleware-jakarta.md) instead.
