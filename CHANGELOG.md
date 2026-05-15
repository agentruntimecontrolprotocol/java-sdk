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

### Tests

32 tests across 12 suites cover the wire round-trip, capability intersection,
agent version resolution, budget arithmetic on `BigDecimal`, lease expiry,
glob matching, idempotency reuse and conflict, subscribe with history replay,
result-chunk reassembly, span emission, the Jetty WebSocket end-to-end, the
Spring Boot end-to-end, and seven conformance dynamic tests.

### Build

JDK 21 LTS floor; toolchain JDK 21; `--release 21`. No `--enable-preview`
required of consumers. Virtual threads drive every per-job worker.
