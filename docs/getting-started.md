---
title: "Getting started"
sdk: java
spec_sections: ["6.1", "6.2", "7.1"]
order: 1
kind: quickstart
since: "1.0.0"
---

# Getting started

## Prerequisites

- **JDK 21+.** Set `JAVA_HOME` to a JDK 21 installation; the Gradle wrapper
  (`./gradlew`) picks it up automatically.
- **Gradle 8.x** via the bundled wrapper — no separate installation needed.

## Add the dependency

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.arcp:arcp:1.0.0")             // umbrella (client + runtime)
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")   // any SLF4J binding
}
```

Use granular artifacts if you only need one side:

```kotlin
implementation("dev.arcp:arcp-client:1.0.0")          // client only
implementation("dev.arcp:arcp-runtime:1.0.0")         // runtime only
implementation("dev.arcp:arcp-runtime-jetty:1.0.0")   // Jetty WebSocket server
implementation("dev.arcp:arcp-otel:1.0.0")            // OpenTelemetry tracing
```

### Maven

```xml
<dependency>
  <groupId>dev.arcp</groupId>
  <artifactId>arcp</artifactId>
  <version>1.0.0</version>
</dependency>
```

## In-process round trip

The smallest working example: a runtime hosting one `echo` agent, paired with
a client over an in-memory transport.

```java
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;

public class Quickstart {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
            .agent("echo", "1.0.0",
                (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                "echo@1.0.0",
                JsonNodeFactory.instance.objectNode().put("hi", 1)));
            System.out.println(handle.result().get().result());
        }
        runtime.close();
    }
}
```

`MemoryTransport.pair()` returns two endpoints of a single in-process channel —
no network required. This pattern is ideal for unit tests and CI smoke checks.

## Over WebSocket

Swap the transport: embed Jetty on the runtime side, use JDK
`HttpClient.WebSocket` on the client side.

```java
import dev.arcp.client.WebSocketTransport;
import dev.arcp.runtime.jetty.ArcpJettyServer;

try (ArcpJettyServer server = ArcpJettyServer.builder(runtime).build().start()) {
    WebSocketTransport transport = WebSocketTransport.connect(server.uri());
    try (ArcpClient client = ArcpClient.builder(transport).build()) {
        client.connect(Duration.ofSeconds(5));
        // … same submit / result as above
    }
}
```

`server.uri()` returns the bound `ws://127.0.0.1:<port>/arcp` address.

## Over stdio (parent–child process)

For subprocess deployments where the agent owns its `stdin` / `stdout`:

```java
import dev.arcp.core.transport.StdioTransport;

// Runtime side (subprocess):
StdioTransport runtimeTransport = StdioTransport.server(System.in, System.out);
runtime.accept(runtimeTransport);

// Client side (parent process, connected via pipes):
StdioTransport clientTransport = StdioTransport.client(processIn, processOut);
try (ArcpClient client = ArcpClient.builder(clientTransport).build()) { … }
```

Both ends use newline-delimited JSON frames (§4.2).

## Bearer auth

By default the runtime accepts any non-empty bearer token. Production
deployments wire a `BearerVerifier`:

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .verifier(BearerVerifier.staticToken("hunter2", new Principal("alice")))
    .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
    .build();

ArcpClient client = ArcpClient.builder(transport)
    .bearer("hunter2")
    .build();
```

A custom HMAC verifier example lives in
[`examples/custom-auth/`](../examples/custom-auth/).

## Next reads

- [Architecture](architecture.md) — envelope, sessions, jobs, leases, errors
- [Transports](transports.md) — WebSocket, Jetty, Spring Boot, Jakarta, Vert.x, stdio
- [Guides](README.md#guides) — one guide per feature area
- [Examples](../examples/) — runnable subprojects covering every feature
- [CONFORMANCE.md](../CONFORMANCE.md) — spec §-keyed implementation status
