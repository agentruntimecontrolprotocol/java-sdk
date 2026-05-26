---
title: "Transports"
sdk: java
spec_sections: ["4", "4.1", "4.2"]
kind: concept
since: "1.0.0"
---

# Transports

A **transport** is anything that implements
[`Transport`](../arcp-core/src/main/java/dev/arcp/core/transport/Transport.java):
two byte-stream endpoints that speak newline-delimited JSON (§4.1). Swap
the transport without touching agent or session code.

## Transport SPI

```java
public interface Transport {
    void send(String frame) throws IOException;
    String receive() throws IOException;   // blocks; null = EOF
    void close();
}
```

All three built-in transports implement both `Transport` and
`AutoCloseable`. Server-side adapters expose a `Transport` per accepted
connection via `runtime.accept(transport)`.

## MemoryTransport (in-process)

The fastest option — no sockets, no threads waiting on I/O:

```java
MemoryTransport[] pair = MemoryTransport.pair();
// pair[0] → runtime side, pair[1] → client side
runtime.accept(pair[0]);
ArcpClient client = ArcpClient.builder(pair[1]).build();
```

`MemoryTransport.pair()` returns two linked endpoints backed by a pair of
`LinkedBlockingQueue<String>` instances. Ideal for unit tests and CI smoke
checks; used in every example that doesn't require network I/O.

## WebSocketTransport (client, §4.1)

JDK `HttpClient.WebSocket` wrapped as a `Transport`. Used on the client
side when the runtime is a remote server:

```java
WebSocketTransport transport = WebSocketTransport.connect(
    URI.create("ws://localhost:8080/arcp"));
try (ArcpClient client = ArcpClient.builder(transport).build()) {
    client.connect(Duration.ofSeconds(5));
    // ...
}
```

`WebSocketTransport.connect(URI)` opens the connection synchronously. The
builder accepts an optional `HttpClient` for TLS, proxies, or custom
headers:

```java
HttpClient http = HttpClient.newBuilder()
    .sslContext(sslContext)
    .build();
WebSocketTransport transport = WebSocketTransport.builder(uri)
    .httpClient(http)
    .header("Authorization", "Bearer " + token)
    .build();
```

## Jetty 12 WebSocket server

`arcp-runtime-jetty` embeds Jetty 12 and binds a WebSocket endpoint at
`/arcp`:

```java
ArcpJettyServer server = ArcpJettyServer.builder(runtime)
    .port(8080)            // 0 = random (useful in tests)
    .build()
    .start();

System.out.println("Listening at " + server.uri());
// → ws://127.0.0.1:8080/arcp

server.close();  // stops Jetty, closes all sessions
```

The server calls `runtime.accept(transport)` for each incoming connection.
TLS: pass a pre-configured `SslContextFactory.Server` to
`ArcpJettyServer.Builder.sslContextFactory(...)`.

## Spring Boot 3.x

`arcp-middleware-spring-boot` provides an auto-configuration that
registers the ARCP WebSocket endpoint when `ArcpRuntime` is a Spring bean:

```java
@SpringBootApplication
public class MyApp {
    @Bean
    ArcpRuntime arcpRuntime() {
        return ArcpRuntime.builder()
            .agent("my-agent", "1.0.0", handler)
            .build();
    }
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

Add to `application.properties`:

```properties
arcp.websocket.path=/arcp       # default
arcp.websocket.enabled=true     # default
```

Dependency:

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-middleware-spring-boot:1.0.0")
```

## Jakarta WebSocket

`arcp-middleware-jakarta` exposes a `ServerEndpointConfig` for any Jakarta
EE 10+ container (Tomcat 10, WildFly 29, GlassFish 7...):

```java
ServerEndpointConfig config = ArcpJakartaEndpoint.configFor(runtime, "/arcp");
// register with your container's ServerContainer, e.g.:
serverContainer.addEndpoint(config);
```

Dependency:

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-middleware-jakarta:1.0.0")
```

## Vert.x 5

`arcp-middleware-vertx` provides a `Handler<ServerWebSocket>` that bridges
each Vert.x WebSocket to the runtime:

```java
ArcpVertxHandler handler = ArcpVertxHandler.create(runtime);

vertx.createHttpServer()
    .webSocketHandler(handler)
    .listen(8080);
```

Dependency:

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-middleware-vertx:1.0.0")
```

## StdioTransport (§4.2)

For subprocess deployments where the agent owns `stdin`/`stdout`:

```java
// Runtime side (subprocess — owns stdin/stdout):
StdioTransport runtimeTransport = StdioTransport.server(System.in, System.out);
runtime.accept(runtimeTransport);

// Client side (parent process — owns the child's pipes):
StdioTransport clientTransport = StdioTransport.client(processIn, processOut);
try (ArcpClient client = ArcpClient.builder(clientTransport).build()) {
    client.connect(Duration.ofSeconds(5));
    // ...
}
```

Both ends use newline-delimited JSON frames (§4.2). The server-side call
`StdioTransport.server(in, out)` redirects `System.err` for agent log
lines so that the protocol frames stay clean on `stdout`.

See the [`examples/stdio/`](../examples/stdio/) runnable subproject for
the full parent/child wiring including process spawning.

## Custom transport

Implement `Transport` directly for any other channel (UNIX domain sockets,
named pipes, message queues...):

```java
public class MyTransport implements Transport, AutoCloseable {
    @Override public void send(String frame) { /* write frame + '\n' */ }
    @Override public String receive() { /* read until '\n'; null on EOF */ }
    @Override public void close() { /* tear down */ }
}
```

Pass to `ArcpClient.builder(new MyTransport())` or
`runtime.accept(new MyTransport())`.

## OpenTelemetry wrapper

`arcp-otel` wraps any transport in an OTel span-per-envelope decorator.
See [guides/observability.md](guides/observability.md).
