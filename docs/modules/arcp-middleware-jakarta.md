---
title: "arcp-middleware-jakarta"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-middleware-jakarta`

Jakarta WebSocket adapter for running `ArcpRuntime` inside any Jakarta EE 9+
Servlet container (Tomcat 10+, WildFly 27+, Payara 6+, etc.).

## Dependency

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-middleware-jakarta:1.0.0")
```

`jakarta.websocket-api` is `compileOnly` — the Servlet container provides it at
runtime.

## Key classes

### `ArcpJakartaAdapter`

Builds a `ServerEndpointConfig` ready to hand to the WebSocket container. The
adapter wires the `ArcpRuntime` into the Jakarta endpoint lifecycle.

```java
ArcpJakartaAdapter adapter = ArcpJakartaAdapter.builder()
    .runtime(runtime)
    .path("/arcp")
    .allowedHosts(List.of("agents.example.com"))
    .allowedOrigins(List.of("https://console.example.com"))
    .build();

// Inside a ServerApplicationConfig or a ServletContextListener:
container.addEndpoint(adapter.serverEndpointConfig());
```

The adapter performs host and origin validation at upgrade time via a custom
`ServerEndpointConfig.Configurator`. Connections that fail either check receive
a 403 response before the WebSocket handshake completes.

### `ArcpJakartaEndpoint`

Jakarta `@ServerEndpoint` used internally by the adapter. Receives each text
frame and forwards it to a `JakartaWebSocketTransport` instance, one per
session.

### `JakartaWebSocketTransport`

Adapts a Jakarta `Session` to the `Transport` SPI. Created per-connection;
not intended for direct instantiation.

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.middleware.jakarta` | `ArcpJakartaAdapter`, `ArcpJakartaEndpoint`, `JakartaWebSocketTransport` |

## Example — Tomcat `ServletContextListener`

```java
@WebListener
public class ArcpListener implements ServletContextListener {

    private ArcpRuntime runtime;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        runtime = ArcpRuntime.builder()
            .agent("my-agent", "1.0.0", handler)
            .verifier(BearerVerifier.staticToken("token", principal))
            .build();

        ServerContainer container =
            (ServerContainer) sce.getServletContext()
                .getAttribute(ServerContainer.class.getName());

        ArcpJakartaAdapter adapter = ArcpJakartaAdapter.builder()
            .runtime(runtime)
            .path("/arcp")
            .build();
        try {
            container.addEndpoint(adapter.serverEndpointConfig());
        } catch (DeploymentException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (runtime != null) {
            runtime.close();
        }
    }
}
```

## When to use

Use `arcp-middleware-jakarta` when:

- Your application already runs in a Jakarta EE / Servlet container
- You want to co-locate the ARCP endpoint with other HTTP routes in the same WAR
- You need fine-grained host or origin filtering via the adapter's builder

For an **embedded** server with no container, prefer
[`arcp-runtime-jetty`](arcp-runtime-jetty.md).
For Spring Boot, prefer
[`arcp-middleware-spring-boot`](arcp-middleware-spring-boot.md).
