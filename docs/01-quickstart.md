---
title: "Quickstart"
sdk: java
spec_sections: ["6.1", "6.2", "7.1"]
order: 1
kind: quickstart
since: "1.0.0"
---

# Quickstart

## Prerequisites

- JDK 21+. Set `JAVA_HOME` to a JDK 21 installation; the Gradle wrapper does
  the rest.
- Gradle 8.x via the bundled wrapper (`./gradlew`).

## Add the dependency

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.arcp:arcp:1.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")   // any SLF4J binding
}
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

The smallest working example: a runtime that hosts one `echo` agent, paired
with a client over an in-memory transport.

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

## Over WebSocket

Swap the transport: embed Jetty on the runtime side, JDK
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

## Bearer auth

By default the runtime accepts any non-empty bearer token; the client sends
an anonymous handshake. Production deployments wire a `BearerVerifier`:

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .verifier(BearerVerifier.staticToken("hunter2", new Principal("alice")))
    .agent(...)
    .build();

ArcpClient client = ArcpClient.builder(transport).bearer("hunter2").build();
```

A custom HMAC verifier example lives in
[`examples/custom-auth/`](../examples/custom-auth/).

## Next reads

- [02-concepts.md](02-concepts.md) — envelope, sessions, jobs, leases, errors
- [03-features/](03-features/) — one short doc per feature
- [04-examples/](../examples/) — ten runnable example subprojects
- [CONFORMANCE.md](../CONFORMANCE.md) — spec-section-keyed status
