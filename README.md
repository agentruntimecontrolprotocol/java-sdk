# ARCP Java SDK

Java SDK for the **Agent Runtime Control Protocol (ARCP)**. Targets JDK 21
LTS; tested on 21. Depends on Jackson and SLF4J only at the `api` level —
no logging binding shipped.

## Install

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.arcp:arcp:1.0.0")            // umbrella
    // or, granular:
    implementation("dev.arcp:arcp-client:1.0.0")     // client only
    implementation("dev.arcp:arcp-runtime:1.0.0")    // runtime only
    implementation("dev.arcp:arcp-runtime-jetty:1.0.0") // WebSocket server
    implementation("dev.arcp:arcp-otel:1.0.0")       // OpenTelemetry tracing
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

## Quickstart

```java
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

class Quickstart {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
            .agent("echo", "1.0.0",
                (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
        runtime.accept(pair[0]);

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(java.time.Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                "echo@1.0.0", JsonNodeFactory.instance.objectNode().put("hi", 1)));
            System.out.println(handle.result().get().result());
        }
        runtime.close();
    }
}
```

For a WebSocket-backed runtime, swap `MemoryTransport` for
`dev.arcp.runtime.jetty.ArcpJettyServer` on the runtime side and
`dev.arcp.client.WebSocketTransport.connect(uri)` on the client side.

## Packaging

| Artifact                       | What's in it                                       | Depends on                      |
| ------------------------------ | -------------------------------------------------- | ------------------------------- |
| `arcp-core`                    | Wire types, errors, capability, ids, lease, transport SPI | none                     |
| `arcp-client`                  | `ArcpClient`, `JobHandle`, `ResultStream`, JDK WebSocket transport | `arcp-core`     |
| `arcp-runtime`                 | `ArcpRuntime`, session FSM, job FSM, lease enforcement, budget counters | `arcp-core` |
| `arcp`                         | Umbrella; re-exports client + runtime              | `arcp-client`, `arcp-runtime`   |
| `arcp-runtime-jetty`           | Embedded Jetty 12 WebSocket server transport       | `arcp-runtime`                  |
| `arcp-middleware-spring-boot`  | Spring Boot 3.x auto-config + WebSocket handler    | `arcp-runtime`                  |
| `arcp-otel`                    | OpenTelemetry adapter (transport-wrapping `Tracer`)| `arcp-core`, `opentelemetry-api`|
| `arcp-tck`                     | Reusable JUnit 5 `@TestFactory` conformance suite  | `arcp-client`, `arcp-runtime`   |

## Layout

```
java-sdk/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── arcp-core/
├── arcp-client/
├── arcp-runtime/
├── arcp/                      # umbrella
├── arcp-runtime-jetty/
├── arcp-otel/
├── arcp-middleware-spring-boot/
├── arcp-tck/
├── docs/diagrams/             # 6 Graphviz diagrams (light + dark SVGs)
└── examples/
    ├── submit-and-stream/
    ├── cancel/
    ├── heartbeat/
    ├── cost-budget/
    ├── result-chunk/
    ├── agent-versions/
    ├── list-jobs/
    ├── lease-expires-at/
    ├── idempotent-retry/
    └── custom-auth/
```

## Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew build               # compile + test all modules
./gradlew :examples:submit-and-stream:run
./gradlew :arcp-runtime-jetty:test
```

## Features

- §5.1 envelope with `arcp: "1"` and `FAIL_ON_UNKNOWN_PROPERTIES=false`
- §6.1 bearer auth via `BearerVerifier` SPI; `acceptAny` and `staticToken` helpers
- §6.2 capability intersection with rich `agents` shape (name + versions + default)
- §6.3 resume buffer (in-memory ring) and rotating `resume_token`
- §6.4 heartbeats: scheduler-driven ping; client and runtime treat two missed
  intervals as `HEARTBEAT_LOST`
- §6.5 `session.ack` with auto-emit rate limit on the client side
- §6.6 `session.list_jobs` scoped to the session's principal
- §7.1 `job.submit` with `lease_request`, `lease_constraints`, `idempotency_key`
- §7.2 idempotency: identical `(principal, key, payload)` reuses the prior `job_id`;
  conflicting payload yields `DUPLICATE_KEY`
- §7.4 cooperative cancellation via `JobContext.cancelled()` + `Thread.interrupt`
- §7.5 agent versioning: `name@version` grammar; bare names resolve to advertised
  default; unknown versions surface `AGENT_VERSION_NOT_AVAILABLE`
- §7.6 subscribe / unsubscribe with optional history replay; subscribers do not
  carry cancel authority
- §8.2 ten event kinds via sealed `EventBody`, including `progress` (current ≥ 0)
- §8.4 `result_chunk` reassembly via `ResultStream` (in-memory or `OutputStream` sink)
- §9 lease grammar + subset check (`Lease.contains`); `LeaseGuard` enforces glob
  patterns with `*` and `**` semantics
- §9.5 lease expiration: strict UTC-`Z` parsing; scheduled watchdog terminates
  jobs whose lease expires while running
- §9.6 `cost.budget` via per-currency `AtomicReference<BigDecimal>` counters
  with `USE_BIG_DECIMAL_FOR_FLOATS` on the wire mapper
- §11 OpenTelemetry trace propagation via `ArcpOtel.withTracing(transport, tracer)`;
  `arcp.session_id` / `arcp.job_id` / `arcp.trace_id` attributes on every span
- §12 fifteen-code error taxonomy with sealed `ArcpException` /
  `RetryableArcpException` / `NonRetryableArcpException` split

## Concurrency

Virtual threads (JEP 444, stable in JDK 21) drive every per-job worker and
every transport publisher. `StructuredTaskScope` is intentionally not used
in published bytecode: it's preview in JDK 21 and finalized in JDK 25 with
a different shape, and the SDK targets `--release 21`.

A single `ScheduledExecutorService` per runtime drives heartbeat ticks and
lease expiry watchdogs; client-side, a similar scheduler emits `session.ack`
and watches the inbound idle timer.

## Conformance

See [CONFORMANCE.md](CONFORMANCE.md) for the spec §-keyed table with file:line
references. Tests at a glance:

- `arcp-core:test` — envelope round-trip, unknown-field tolerance, capability
  intersection, feature decode
- `arcp-runtime:test` — agent version resolution, budget counters,
  lease guard, expiry, subset checks
- `arcp-client:test` — smoke round-trip, idempotency reuse + conflict,
  subscribe with history replay, result-chunk reassembly
- `arcp-otel:test` — outbound + inbound spans through `InMemorySpanExporter`
- `arcp-runtime-jetty:test` — end-to-end client + runtime over loopback WebSocket
- `arcp-middleware-spring-boot:test` — Spring Boot 3.x autoconfig + WebSocket
  handler driven from a `@SpringBootTest` with an embedded Tomcat
- `arcp-tck:test` — seven dynamic conformance tests via JUnit `@TestFactory`,
  reusable by downstream JVM implementations

Diagrams under [`docs/diagrams/`](docs/diagrams/): module graph, session
lifecycle, job lifecycle, capability negotiation, heartbeat + ack, result-chunk
reassembly. Light + dark variants render via `make -C docs/diagrams`.

## License

[Apache-2.0](./LICENSE).
