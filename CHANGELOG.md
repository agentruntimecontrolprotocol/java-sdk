# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0]

Initial release of the ARCP Java SDK.

### Modules

- `arcp-core`: wire types, envelope, capability negotiation, sealed message and
  event taxonomies, ULID-based ids, lease + constraints, in-memory transport.
- `arcp-client`: `ArcpClient`, `JobHandle`, `ResultStream`, `WebSocketTransport`
  (JDK `HttpClient.WebSocket`), replaying event publisher.
- `arcp-runtime`: `ArcpRuntime`, session FSM, job FSM, `LeaseGuard`,
  `BudgetCounters`, `IdempotencyStore`, `AgentRegistry`, heartbeat tracker,
  resume buffer, expiry watchdog.
- `arcp`: umbrella artifact re-exporting client + runtime.
- `arcp-runtime-jetty`: embedded Jetty 12 WebSocket server transport.
- `arcp-otel`: OpenTelemetry adapter (transport-wrapping `Tracer`), W3C
  traceparent injection/extraction via the `extensions` payload field.
- `arcp-middleware-spring-boot`: Spring Boot 3.x auto-configuration registers
  an `ArcpWebSocketHandler` at the configured path whenever an `ArcpRuntime`
  bean is declared.
- `arcp-middleware-jakarta`: plain Jakarta WebSocket adapter exposing
  `ArcpJakartaAdapter.serverEndpointConfig()` for consumers running their own
  Servlet container (Tomcat, Undertow, Jetty in Servlet mode).
- `arcp-middleware-vertx`: Vert.x 5 `Handler<ServerWebSocket>` adapter with
  path and Host-header allowlist gates.
- `arcp-tck`: reusable JUnit 5 `@TestFactory` conformance suite parameterised
  by a `TckProvider` SPI, so downstream JVM ARCP implementations can ride the
  same checks.

### Examples

Ten runnable example subprojects: submit-and-stream, cancel, heartbeat,
cost-budget, result-chunk, agent-versions, list-jobs, lease-expires-at,
idempotent-retry, custom-auth. Each prints `OK <name>` on success and asserts
under `-ea`.

### Diagrams

Six Graphviz diagrams (light + dark variants, 12 SVGs total) under
`docs/diagrams/`: module graph, session lifecycle, job lifecycle, capability
negotiation, heartbeat + ack, result-chunk reassembly. Render with
`make -C docs/diagrams`.

### Documentation

A prose tree under `docs/` mirroring the spec layout: `00-overview.md`,
`01-quickstart.md`, `02-concepts.md`, `03-features/*` (heartbeats, ack,
list-jobs, subscribe, progress, result-chunk, lease-expires-at, cost-budget,
agent-versions), and `05-reference/*` (api-overview, error-taxonomy,
wire-format).

### Tests

40 tests across 18 suites cover the wire round-trip, capability intersection,
agent version resolution, budget arithmetic on `BigDecimal`, lease expiry,
glob matching, idempotency reuse and conflict, subscribe with history replay,
result-chunk reassembly, span emission, the Jetty / Jakarta / Vert.x /
Spring Boot WebSocket end-to-ends, the stdio newline-delimited JSON
end-to-end, seven conformance dynamic tests, and three jqwik property tests
(envelope round-trip + unknown-field tolerance, BigDecimal budget arithmetic,
arbitrary result-chunk arrival-order reassembly).

PIT mutation testing is wired as an opt-in Gradle task on `arcp-core` and
`arcp-runtime` (`./gradlew :arcp-core:pitest`); it is intentionally not
gated on PR builds because mutation runs are 5–10× the unit-test pass.

### Transports

A new newline-delimited JSON `StdioTransport` (`§4.2`) under
`dev.arcp.core.transport`, with a round-trip test that wires both peers
through a queue-backed pipe pair. Suitable for parent–child process
deployments where the agent rides as a subprocess and the parent owns
stdin/stdout.

### Publishing

Maven Central scaffolding consolidated into the root `build.gradle.kts`:
single licence block per POM, full developer info, SCM URLs, GitHub
issue tracker, Sonatype staging endpoints (snapshots vs releases auto-
selected by `version`), and PGP signing wired through
`signingKey` / `signingPassword` (or `GPG_SIGNING_KEY` /
`GPG_SIGNING_PASSWORD`). Signing is required only when the keys are
supplied; local builds skip it silently.

### Build

JDK 21 LTS floor; toolchain JDK 21; `--release 21`. No `--enable-preview`
required of consumers. Virtual threads drive every per-job worker.
