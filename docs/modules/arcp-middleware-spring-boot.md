---
title: "arcp-middleware-spring-boot"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-middleware-spring-boot`

Spring Boot auto-configuration for `ArcpRuntime`. Drop in the dependency and
declare an `ArcpRuntime` bean — the WebSocket endpoint registers itself.

## Dependency

```kotlin
implementation("io.agentruntimecontrolprotocol:arcp-middleware-spring-boot:1.0.0")
```

## Auto-configuration

`ArcpSpringBootAutoConfiguration` activates when:

1. `EnableWebSocket` and `WebSocketConfigurer` are on the classpath, **and**
2. An `ArcpRuntime` bean is present in the application context.

No `@EnableWebSocket` annotation is needed in your own code; the auto-config
carries it.

## Configuration properties

Bound at `arcp.middleware.*` in `application.yml` / `application.properties`:

| Property | Default | Description |
|---|---|---|
| `arcp.middleware.path` | `/arcp` | WebSocket endpoint path |
| `arcp.middleware.allowed-hosts` | `[]` (all) | Host header allowlist |
| `arcp.middleware.allowed-origins` | `[]` (all) | Origin header allowlist |

```yaml
arcp:
  middleware:
    path: /arcp
    allowed-origins:
      - https://console.example.com
    allowed-hosts:
      - agents.example.com
```

## Key classes

### `ArcpSpringBootAutoConfiguration`

Implements `WebSocketConfigurer`. Registers `ArcpWebSocketHandler` at the
configured path and sets allowed origins.

### `ArcpWebSocketHandler`

Spring `WebSocketHandler` that adapts each `WebSocketSession` to a
`SpringWebSocketTransport` and calls `runtime.accept(transport)`.

### `ArcpSpringBootProperties`

`@ConfigurationProperties("arcp.middleware")` binding class. Injected into
the auto-configuration.

### `SpringWebSocketTransport`

Adapts a Spring `WebSocketSession` to the `Transport` SPI. Created per
connection.

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.middleware.spring` | `ArcpSpringBootAutoConfiguration`, `ArcpWebSocketHandler`, `ArcpSpringBootProperties`, `SpringWebSocketTransport` |

## Minimal Spring Boot application

```java
@SpringBootApplication
public class AgentServer {

    public static void main(String[] args) {
        SpringApplication.run(AgentServer.class, args);
    }

    @Bean
    public ArcpRuntime arcpRuntime() {
        return ArcpRuntime.builder()
            .agent("my-agent", "1.0.0", (input, ctx) -> {
                ctx.emit(LogEvent.info("hello"));
                return JobOutcome.Success.inline(input.payload());
            })
            .verifier(BearerVerifier.staticToken("secret", principal))
            .build();
    }
}
```

With `arcp-middleware-spring-boot` on the classpath and the `ArcpRuntime` bean
declared, the WebSocket endpoint is available at `ws://localhost:8080/arcp`
automatically.

## When to use

Use `arcp-middleware-spring-boot` when your application is a Spring Boot
service and you want zero-configuration WebSocket integration. For non-Spring
containers, use [`arcp-middleware-jakarta`](arcp-middleware-jakarta.md) or
[`arcp-runtime-jetty`](arcp-runtime-jetty.md).
