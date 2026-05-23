<h3 align="center">ARCP Java SDK</h3>

<p align="center"><strong>Java SDK for the Agent Runtime Control Protocol (ARCP) — submit, observe, and control long-running agent jobs from Java.</strong></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/dev.arcp/arcp"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/dev.arcp/arcp.svg"></a>
  <a href="https://github.com/agentruntimecontrolprotocol/java-sdk/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/agentruntimecontrolprotocol/java-sdk/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md"><img alt="ARCP" src="https://img.shields.io/badge/ARCP-v1.1%20draft-blue"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-lightgrey"></a>
</p>

<p align="center">
  <a href="https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md">Specification</a> ·
  <a href="#concepts">Concepts</a> ·
  <a href="#installation">Install</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="docs/">Guides</a> ·
  <a href="docs/modules/arcp.md">API reference</a>
</p>

---

`dev.arcp:arcp` is the Java reference implementation of [ARCP](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md), the Agent Runtime Control Protocol. It covers both sides of the wire — `arcp-client` for submitting and observing jobs, `arcp-runtime` for hosting agents (with `arcp-runtime-jetty` for networked WebSocket runtimes) — so either side can talk to any conformant peer in any language without hand-rolling the envelope, sequencing, or lease enforcement.

ARCP itself is a transport-agnostic wire protocol for long-running AI agent jobs. It owns the parts of agent infrastructure that don't change between products — sessions, durable event streams, capability leases, budgets, resume — and stays out of the parts that do. ARCP wraps the agent function; it does not define how agents are built, how tools are exposed (that's MCP), or how telemetry is exported (that's OpenTelemetry).

## Installation

Requires JDK 21 LTS. Artifacts are published to Maven Central as `dev.arcp:*`; the `arcp` umbrella re-exports `arcp-client` and `arcp-runtime`. Pull just the side you need à la carte, or pick up framework middleware (`arcp-middleware-spring-boot`, `-jakarta`, `-vertx`), the OpenTelemetry adapter (`arcp-otel`), and the Jetty WebSocket runtime (`arcp-runtime-jetty`) as needed:

```kotlin
// Gradle (Kotlin DSL)
dependencies {
    implementation("dev.arcp:arcp:1.0.0")                  // umbrella (client + runtime)
    // or, à la carte:
    implementation("dev.arcp:arcp-client:1.0.0")           // client side
    implementation("dev.arcp:arcp-runtime:1.0.0")          // runtime side
    implementation("dev.arcp:arcp-runtime-jetty:1.0.0")    // WebSocket server
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>dev.arcp</groupId>
  <artifactId>arcp</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Quick start

Connect to a runtime, submit a job, stream its events to completion:

```java
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.WebSocketTransport;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.messages.JobResult;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

var transport = WebSocketTransport.connect(URI.create("wss://runtime.example.com/arcp"));
try (var client = ArcpClient.builder(transport)
        .client("quickstart", "1.0.0")
        .bearer(System.getenv("ARCP_TOKEN"))
        .build()) {

    client.connect(Duration.ofSeconds(5));

    var input = JsonNodeFactory.instance.objectNode().put("dataset", "s3://example/sales.csv");
    JobHandle handle = client.submit(ArcpClient.jobSubmit("data-analyzer", input));

    handle.events().subscribe(new Flow.Subscriber<EventBody>() {
        public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        public void onNext(EventBody body) { System.out.println(body.kind() + " " + body); }
        public void onError(Throwable t) {}
        public void onComplete() {}
    });

    JobResult result = handle.result().get(60, TimeUnit.SECONDS);
    System.out.println("final: " + result.finalStatus() + " " + result.result());
}
```

This is the whole shape of the SDK: open a session, submit work, consume an ordered event stream, get a terminal result or error. Everything below is detail on those four moves.

## Concepts

ARCP organizes everything around four concerns — **identity**, **durability**, **authority**, and **observability** — expressed through five core objects:

- **Session** — a connection between a client and a runtime. A session carries identity (a bearer token), negotiates a feature set in a `hello`/`welcome` handshake, and is *resumable*: if the transport drops, you reconnect with a resume token and the runtime replays buffered events. Jobs outlive the session that started them. See [§6](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Job** — one unit of agent work submitted into a session. A job has an identity, an optional idempotency key, a resolved agent version, and a lifecycle that ends in exactly one terminal state: `success`, `error`, `cancelled`, or `timed_out`. See [§7](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Event** — the ordered, session-scoped stream a job emits: logs, thoughts, tool calls and results, status, metrics, artifact references, progress, and streamed result chunks. Events carry strictly monotonic sequence numbers so the stream survives reconnects gap-free. See [§8](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Lease** — the authority a job runs under, expressed as capability grants (`fs.read`, `fs.write`, `net.fetch`, `tool.call`, `agent.delegate`, `cost.budget`, `model.use`). The runtime enforces the lease at every operation boundary; a job can never act outside it. Leases may carry a budget and an expiry, and may be subset and handed to sub-agents via delegation. See [§9](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Subscription** — read-only attachment to a job started elsewhere (e.g. a dashboard watching a job a CLI submitted). A subscriber observes the live event stream but cannot cancel or mutate the job. Distinct from *resume*, which continues the original session and carries cancel authority. See [§7.6](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).

The SDK models each of these as first-class objects; the rest of this README shows how.

## Guides

### Sessions and resume

Open a session, negotiate features, and reconnect transparently after a transport drop using the resume token — jobs keep running server-side while you're gone.

```java
import dev.arcp.client.ArcpClient;
import dev.arcp.client.Session;
import dev.arcp.client.WebSocketTransport;
import java.net.URI;
import java.time.Duration;

URI uri = URI.create("wss://runtime.example.com/arcp");

// First connection: capture resume token and last seen sequence.
String resumeToken;
long lastSeq;
try (var client = ArcpClient.builder(WebSocketTransport.connect(uri))
        .client("resumable", "1.0.0")
        .bearer(System.getenv("ARCP_TOKEN"))
        .build()) {
    Session session = client.connect(Duration.ofSeconds(5));
    // ... submit jobs, stream events ...
    resumeToken = session.resumeToken();
    lastSeq = client.lastSeenSeq();
}

// ... transport drops ...

// Second connection: resume the same session; the runtime replays every
// event with seq > lastSeq, then resumes live streaming.
try (var client = ArcpClient.builder(WebSocketTransport.connect(uri))
        .client("resumable", "1.0.0")
        .bearer(System.getenv("ARCP_TOKEN"))
        .resumeToken(resumeToken)
        .lastEventSeq(lastSeq)
        .build()) {
    client.connect(Duration.ofSeconds(5));
    // ... continue ...
}
```

### Submitting jobs

Submit a job with an agent (optionally version-pinned as `name@version`), an input, and an optional lease request, idempotency key, and runtime limit.

```java
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

var input = JsonNodeFactory.instance.objectNode().put("week", "2026-W19");
Lease lease = Lease.builder().allow("net.fetch", "s3://reports/**").build();
LeaseConstraints constraints = LeaseConstraints.of(Instant.now().plus(1, ChronoUnit.MINUTES));

JobHandle handle = client.submit(ArcpClient.jobSubmit(
        "weekly-report@2.1.0",
        input,
        lease,
        constraints,
        "weekly-report-2026-W19",   // idempotency_key
        300));                       // max_runtime_sec

System.out.println("job_id          = " + handle.jobId());
System.out.println("resolved agent  = " + handle.resolvedAgent());
System.out.println("effective lease = " + handle.accepted().lease());
```

### Consuming events

Iterate the ordered event stream — `log`, `thought`, `tool_call`, `tool_result`, `status`, `metric`, `artifact_ref`, `progress`, `result_chunk` — and optionally acknowledge progress so the runtime can release buffered events early.

```java
import dev.arcp.client.ArcpClient;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.events.ProgressEvent;
import dev.arcp.core.events.ToolCallEvent;
import java.time.Duration;
import java.util.concurrent.Flow;

// autoAck is on by default; coalesced session.ack is emitted every 200ms.
try (var client = ArcpClient.builder(transport)
        .client("ack-demo", "1.0.0")
        .autoAck(true)
        .ackInterval(Duration.ofMillis(250))
        .build()) {
    client.connect(Duration.ofSeconds(5));
    var handle = client.submit(ArcpClient.jobSubmit("data-analyzer", input));

    handle.events().subscribe(new Flow.Subscriber<EventBody>() {
        public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        public void onNext(EventBody body) {
            switch (body) {
                case LogEvent log         -> System.out.println(log.message());
                case ToolCallEvent call   -> System.out.println("-> tool " + call);
                case MetricEvent metric   -> System.out.println("metric " + metric.name() + "=" + metric.value());
                case ProgressEvent prog   -> System.out.println("progress " + prog);
                default                   -> {}
            }
            // Or ack manually: client.ack(currentSeq);
        }
        public void onError(Throwable t) {}
        public void onComplete() {}
    });
}
```

### Leases and budgets

Request capabilities, a budget, and an expiry; read budget-remaining metrics as they arrive; handle the runtime's enforcement decisions.

```java
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

Lease lease = Lease.builder()
        .allow("tool.call", "search.*", "fetch.*")
        .allow("cost.budget", "USD:1.00")
        .build();
LeaseConstraints constraints = LeaseConstraints.of(Instant.now().plus(10, ChronoUnit.MINUTES));

JobHandle handle = client.submit(ArcpClient.jobSubmit(
        "web-research",
        JsonNodeFactory.instance.objectNode().put("iterations", 8),
        lease,
        constraints,
        null,
        null));

System.out.println("initial budget = " + lease.budget());

handle.events().subscribe(new Flow.Subscriber<EventBody>() {
    public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
    public void onNext(EventBody body) {
        if (body instanceof MetricEvent m && "cost.budget.remaining".equals(m.name())) {
            System.out.printf("budget remaining: %s %s%n", m.value(), m.unit());
        }
    }
    public void onError(Throwable t) {}
    public void onComplete() {}
});

try {
    handle.result().get();
} catch (ExecutionException e) {
    // BUDGET_EXHAUSTED or LEASE_EXPIRED is never retryable.
    if (e.getCause() instanceof BudgetExhaustedException
            || e.getCause() instanceof LeaseExpiredException) {
        throw e;
    }
}
```

### Subscribing to jobs

Attach read-only to a job submitted elsewhere and observe its live stream (with optional history replay) without cancel authority.

```java
import dev.arcp.client.ArcpClient;
import dev.arcp.client.Page;
import dev.arcp.client.SubscribeOptions;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.core.messages.JobSummary;
import java.util.List;
import java.util.concurrent.Flow;

try (var observer = ArcpClient.builder(transport)
        .client("dashboard", "1.0.0")
        .bearer(System.getenv("ARCP_TOKEN"))
        .build()) {
    observer.connect(java.time.Duration.ofSeconds(5));

    Page<JobSummary> running = observer.listJobs(
            new JobFilter(List.of("running"), null, null));

    Flow.Publisher<EventBody> events = observer.subscribe(
            running.items().get(0).jobId(),
            SubscribeOptions.withHistory(0L));   // replay from seq=0 then go live

    events.subscribe(new Flow.Subscriber<EventBody>() {
        public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        public void onNext(EventBody body) { System.out.println(body.kind() + " " + body); }
        public void onError(Throwable t) {}
        public void onComplete() {}
    });
}
```

### Error handling

Catch the typed error taxonomy and respect the `retryable` flag — `LEASE_EXPIRED` and `BUDGET_EXHAUSTED` are never retryable; a naive retry fails identically.

```java
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.error.LeaseExpiredException;
import java.util.concurrent.ExecutionException;

try {
    var handle = client.submit(ArcpClient.jobSubmit("flaky", input));
    handle.result().get();
} catch (ExecutionException e) {
    if (e.getCause() instanceof ArcpException ex) {
        if (ex instanceof LeaseExpiredException || ex instanceof BudgetExhaustedException) {
            throw e;                  // resubmit with a fresh lease / budget instead
        }
        if (ex.retryable()) {
            // safe to retry with backoff (e.g. INTERNAL_ERROR, TIMEOUT)
        }
    }
    throw e;
}
```

## Feature support

ARCP features this SDK negotiates during the `hello`/`welcome` handshake:

| Feature flag | Status |
|---|---|
| `heartbeat` | Supported |
| `ack` | Supported |
| `list_jobs` | Supported |
| `subscribe` | Supported |
| `lease_expires_at` | Supported |
| `cost.budget` | Supported |
| `model.use` | Supported |
| `provisioned_credentials` | Supported |
| `progress` | Supported |
| `result_chunk` | Supported |
| `agent_versions` | Supported |

## Transport

ARCP is transport-agnostic. This SDK ships a WebSocket transport (default, JDK `HttpClient`-based), an stdio transport for in-process child runtimes, and an in-memory transport for tests. WebSocket is the default for networked runtimes; stdio is used for in-process child runtimes. Select one by constructing the corresponding `Transport` (`WebSocketTransport.connect(uri)`, `new StdioTransport(in, out)`, `MemoryTransport.pair()`) and passing it to `ArcpClient.builder(transport)`; server-side WebSocket hosting is provided by `arcp-runtime-jetty`, with framework middleware in `arcp-middleware-spring-boot`, `arcp-middleware-jakarta`, and `arcp-middleware-vertx`.

## API reference

Full API reference — every type, method, and event payload — is in [`docs/`](docs/), with per-module pages under [`docs/modules/`](docs/modules/) and topic guides under [`docs/guides/`](docs/guides/).

## Versioning and compatibility

This SDK speaks **ARCP v1.1 (draft)**. The SDK follows semantic versioning independently of the protocol; the protocol version it negotiates is shown above and in `session.hello`. A runtime advertising a different ARCP MAJOR is not guaranteed compatible. Feature mismatches degrade gracefully: the effective feature set is the intersection of what the client and runtime advertise, and the SDK will not use a feature outside it.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Protocol questions and proposed changes belong in the [spec repository](https://github.com/agentruntimecontrolprotocol/spec); SDK bugs and feature requests belong here.

## License

Apache-2.0 — see [`LICENSE`](LICENSE).
