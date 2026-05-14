# 06 — Examples (Java mapping of the TS suite)

## Inventory check

The TS suite (`../typescript-sdk/examples/`) ships **22 example
directories**, not the 18 the BOOTSTRAP cites. Counted by
`ls examples/ | grep -v '^node_modules$\|^README\|^package\|^tsconfig'`.
TS `README.md` self-describes as "twenty-three" — off by one against
its own filesystem (the README counts `tracing` once but lists it
once, so it's a stale prose count, not a missing directory).

Java target count: **20 example subprojects**. Three TS examples
(`express`, `fastify`, `bun`) collapse into one Java
`framework-integration` example because the Java story is one
framework adapter (Spring Boot) with a Jakarta adapter mentioned in
the same README — the same WebSocket upgrade seam, three times, is
not a Java story. The remaining 19 TS examples each get a dedicated
Java subproject, plus `framework-integration` = 20.

## Per-example mapping

One row per TS example, in TS `README.md` order (v1.0 core, then v1.1
features, then host integrations). "Files" column is the minimum
fileset; `README.md` and `build.gradle.kts` are implicit on every row.

### v1.0 core (9 TS examples → 8 Java subprojects)

| # | TS example          | Java example subproject path                  | Files                              | Spec §            | Java idiom demonstrated                                                                                                                                                                                            |
| - | ------------------- | --------------------------------------------- | ---------------------------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1 | `submit-and-stream` | `examples/submit-and-stream/`                 | `Server.java`, `Client.java`       | §13.1, §7.1, §8.2 | Agent body runs on a virtual thread inside `StructuredTaskScope`; emits 7 event kinds through a `SubmissionPublisher<Event>`; client consumes via `Flow.Subscriber.onSubscribe` with `request(Long.MAX_VALUE)` and prints to stdout |
| 2 | `delegate`          | `examples/delegate/`                          | `Server.java`, `Client.java`       | §13.2, §10        | Parent emits a `delegate` event; runtime spawns a child `StructuredTaskScope.ShutdownOnFailure` fork that inherits parent's `TraceId`; child `LeaseRequest` rejected if not a subset of parent's effective lease via `Lease.contains(LeaseRequest)` |
| 3 | `resume`            | `examples/resume/`                            | `Server.java`, `Client.java`       | §13.3, §6.3       | Client closes its `HttpClient.WebSocket` mid-stream, then re-opens with `SessionHello.builder().resumeToken(prev).lastEventSeq(seenSeq)`; runtime replays from an in-memory `RingBuffer<Event>` keyed by `SessionId`; `AtomicReference<ResumeToken>` rotated and surfaced on the new `session.welcome` |
| 4 | `idempotent-retry`  | `examples/idempotent-retry/`                  | `Server.java`, `Client.java`       | §13.5, §7.2       | `ArcpClient.submit(JobRequest.builder().idempotencyKey("weekly-2024-W42").agent("weekly-report").build())` called twice returns the same `JobId`; second call with a different agent throws `DuplicateKeyException` (sealed under `NonRetryableArcpException`); idempotency table is `ConcurrentHashMap<IdempotencyKey, JobId>` keyed by `(Principal, IdempotencyKey)` |
| 5 | `lease-violation`   | `examples/lease-violation/`                   | `Server.java`, `Client.java`       | §13.4, §9.3       | Agent calls `LeaseGuard.validate("fs.read", Path.of("/etc/passwd"))` which throws `PermissionDeniedException`; agent catches and surfaces it inside a `tool_result` body via `ctx.toolResult(callId, ToolResult.error(...))`, then continues — the exception is NOT propagated to the `StructuredTaskScope`'s join |
| 6 | `cancel`            | `examples/cancel/`                            | `Server.java`, `Client.java`       | §7.4              | Long-running agent loop checks `Thread.currentThread().isInterrupted()` each tick; client `handle.cancel()` sends `job.cancel` and the runtime calls `Subtask.fork`'s associated `Thread.interrupt()`; the scope's `joinUntil` unwinds and the runtime emits `job.error { final_status: "cancelled" }` |
| 7 | `stdio`             | `examples/stdio/`                             | `Client.java`, `ServerMain.java`   | §4.2, §22         | Client spawns child JVM via `ProcessBuilder` and wires `StdioTransport` over the child's `process.getInputStream()` / `process.getOutputStream()`; framing is newline-delimited JSON; stderr is reserved for SLF4J output via `slf4j-simple` configured to `System.err` |
| 8 | `vendor-extensions` | `examples/vendor-extensions/`                 | `Server.java`, `Client.java`       | §8.2, §9.2, §15   | Agent emits `Event.vendor("x-vendor.acme.progress", payload)`; client registers `EventDecoder.forNamespace("x-vendor.acme.*", AcmeProgress.class)` via the `ExtensionRegistry` SPI; unregistered namespaces decode to `Event.Unknown(String namespace, JsonNode raw)` and skip without error |
| 9 | `custom-auth`       | `examples/custom-auth/`                       | `Server.java`, `Client.java`, `HmacBearerVerifier.java` | §6.1 | Implements the `BearerVerifier` SPI (`@FunctionalInterface BearerVerifier { BearerIdentity verify(String token) throws UnauthenticatedException; }`); HMAC-SHA-256 via `javax.crypto.Mac.getInstance("HmacSHA256")` and `MessageDigest.isEqual` for constant-time compare; bad tokens map to `UNAUTHENTICATED` at the handshake seam, before any `session.welcome` is sent |

### v1.1 features (9 TS examples → 9 Java subprojects)

| #  | TS example          | Java example subproject path        | Files                        | Spec §       | Java idiom demonstrated                                                                                                                                                                                                                                       |
| -- | ------------------- | ----------------------------------- | ---------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 10 | `heartbeat`         | `examples/heartbeat/`               | `Server.java`, `Client.java` | §6.4         | Runtime schedules `session.ping` on `Executors.newSingleThreadScheduledExecutor()` at `heartbeat_interval_sec` advertised in `session.welcome`; client replies with `session.pong { nonce, received_at }`; `Awaitility.await().atMost(Duration.ofSeconds(10)).until(...)` asserts at least 2 pings observed |
| 11 | `ack-backpressure`  | `examples/ack-backpressure/`        | `Server.java`, `Client.java` | §6.5, §8.2   | Per-session `AtomicLong lastProcessedSeq`; client emits `session.ack` at most every 200ms via a `RateLimiter` (one boolean CAS, no lock); runtime tracks unacked lag, and when lag > `backPressureThreshold` (200) emits `Event.status(Phase.BACK_PRESSURE)` — advisory, not flow-controlling on the wire |
| 12 | `list-jobs`         | `examples/list-jobs/`               | `Server.java`, `Client.java` | §6.6         | `ArcpClient.listJobs(JobFilter.status(JobStatus.RUNNING))` returns `Page<JobSummary>` with `Optional<String> nextCursor`; iteration is `Stream.iterate(firstPage, Page::hasNext, p -> client.listJobs(filter, p.cursor()))`; runtime enforces per-`Principal` scope and never leaks job existence across principals |
| 13 | `subscribe`         | `examples/subscribe/`               | `Server.java`, `Submitter.java`, `Observer.java` | §7.6, §6.6 | Two `ArcpClient` instances under the same principal; observer calls `client.subscribe(jobId, SubscribeOptions.builder().history(true).fromSeq(0L).build())` returning `Flow.Publisher<Event>` backed by `SubmissionPublisher`; cross-session `client.cancel(jobId)` from observer throws `PermissionDeniedException` (subscribe authority ⊄ cancel authority) |
| 14 | `agent-versions`    | `examples/agent-versions/`          | `Server.java`, `Client.java` | §7.5, §12    | `AgentRef.parse("code-refactor@2.0.0")` returns `AgentRef(String name, Optional<SemVer> version)`; runtime's `AgentRegistry.resolve(AgentRef)` consults the rich `agents:` capability; unregistered version throws `AgentVersionNotAvailableException` (sealed under `NonRetryableArcpException`) — `retryable: false` |
| 15 | `lease-expires-at`  | `examples/lease-expires-at/`        | `Server.java`, `Client.java` | §9.5, §12    | `LeaseConstraints.expiresAt` is `Instant` only; `Instant.parse(raw)` at SDK seam rejects strings whose offset is not `Z` (custom `OffsetDateTime`-then-coerce check, because `Instant.parse` already accepts only `Z` — the negative test asserts `+00:00` is rejected); runtime watchdog uses `ScheduledExecutorService.schedule(task, until.toEpochMilli() - now, MILLISECONDS)` |
| 16 | `cost-budget`       | `examples/cost-budget/`             | `Server.java`, `Client.java` | §9.6, §12    | Per-currency counters: `ConcurrentHashMap<String, AtomicReference<BigDecimal>>` keyed by ISO-4217 code; decrement is a CAS loop `ref.updateAndGet(b -> b.subtract(metric.value()))`; metric values decoded via Jackson's `USE_BIG_DECIMAL_FOR_FLOATS` (set at `ArcpMapper.create()`); budget exhaustion throws `BudgetExhaustedException` (`retryable: false`) before the next authority-bearing op |
| 17 | `progress`          | `examples/progress/`                | `Server.java`, `Client.java` | §8.2.1       | `record ProgressEvent(int current, OptionalInt total, Optional<String> units, Optional<String> message) implements Event.Body { public ProgressEvent { if (current < 0) throw new IllegalArgumentException("current"); total.ifPresent(t -> { if (current > t) throw new IllegalArgumentException("current > total"); }); } }` — invariants in the canonical constructor, validated on both emit and decode |
| 18 | `result-chunk`      | `examples/result-chunk/`            | `Server.java`, `Client.java` | §8.4         | Runtime produces `Flow.Publisher<ResultChunk>`; client consumes via `Flow.Subscriber.onSubscribe(sub -> sub.request(N))` with N driven by sink readiness, then writes to a `FileChannel` via `channel.write(chunk.data())` (NOT `transferFrom`, which is socket-side) — avoids 30MB heap retention; assembled file size asserted against terminal `job.result.resultSize` |

### Host integrations (4 TS examples → 2 Java subprojects)

| #  | TS example | Java example subproject path             | Files                          | Spec § | Java idiom demonstrated                                                                                                                                                                                                                                       |
| -- | ---------- | ---------------------------------------- | ------------------------------ | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 19 | `tracing`  | `examples/tracing/`                      | `Server.java`, `Client.java`   | §11    | Both sides install the `arcp-otel` middleware (Phase 5); inbound envelope's `payload.extensions["x-vendor.opentelemetry.tracecontext"]` is extracted with `W3CTraceContextPropagator` into `Context.current()`, then re-injected on outbound; `arcp.lease.expires_at` and `arcp.budget.remaining` attributes set on session/job spans; `ConsoleSpanExporter` prints spans, no collector needed |
| 20 | `express`, `fastify`, `bun` | `examples/framework-integration/` | `SpringBootServer.java`, `JakartaServer.java`, `Client.java` | §4.1 | Folded — Java has one framework story per stack. Spring Boot variant: `@Bean ArcpRuntime` registered with `WebSocketHandler` on `/arcp` via `WebSocketConfigurer`; HTTP `/health` route on the same `ServletContext`; Jakarta variant: `@ServerEndpoint("/arcp")` on Tomcat 11. DNS-rebind allow-list on the `Host` header via a Servlet `Filter`. One README compares the two adapters; not three trivial examples of "WebSocket upgrade alongside HTTP routes" |

That is 20 Java example subprojects covering all 22 TS examples. The
`express`/`fastify`/`bun` collapse is justified: each TS example
differs only in which Node framework hosts the WebSocket upgrade, and
Java's equivalent question is Servlet vs Jakarta — there is no
third defensible Java adapter.

## Common harness

Every Java example follows this shape, so a reviewer can predict the
structure of an unread example:

```
examples/<name>/
├── README.md                                           ← spec § quoted; what the example proves; expected stdout
├── build.gradle.kts                                    ← plugins { id("application") }; application { mainClass = "dev.arcp.examples.<name>.Main" }
├── src/main/java/dev/arcp/examples/<name>/Main.java    ← entry point; wires Server + Client; exits 0 on assertion success
├── src/main/java/dev/arcp/examples/<name>/Server.java  ← (optional) when the example splits server/client logic
└── src/main/java/dev/arcp/examples/<name>/Client.java  ← (optional)
```

Runner: `./gradlew :examples:<name>:run`. Exit code 0 on success;
non-zero on any `AssertionError` thrown from `Main.main`. Examples
run end-to-end **in-process via `MemoryTransport`** UNLESS the
example specifically demonstrates a wire-level behaviour, in which
case the server binds to `127.0.0.1:0` (ephemeral port) and the
client reads the bound address from a `CompletableFuture<URI>`.

WebSocket-required examples: `heartbeat` (timer cadence), `resume`
(reconnect), `stdio` (child process), `tracing` (header injection
across the wire), `framework-integration` (the whole point).

`MemoryTransport`-only examples (15 of 20): all the rest. In-process
keeps CI fast and removes port-collision flakes.

Each example's `Main` ends with one or more `assert` statements (run
under `-ea`, enforced by `application { applicationDefaultJvmArgs =
listOf("-ea") }` in the Gradle template). Failures throw
`AssertionError` and the process exits non-zero.

## `settings.gradle.kts` impact

Register each Java example as a subproject under the existing
`:examples` umbrella:

```kotlin
include(
    ":examples:submit-and-stream",
    ":examples:delegate",
    ":examples:resume",
    ":examples:idempotent-retry",
    ":examples:lease-violation",
    ":examples:cancel",
    ":examples:stdio",
    ":examples:vendor-extensions",
    ":examples:custom-auth",
    ":examples:heartbeat",
    ":examples:ack-backpressure",
    ":examples:list-jobs",
    ":examples:subscribe",
    ":examples:agent-versions",
    ":examples:lease-expires-at",
    ":examples:cost-budget",
    ":examples:progress",
    ":examples:result-chunk",
    ":examples:tracing",
    ":examples:framework-integration",
)
```

The current single-tree `:examples` subproject (one
`examples/build.gradle.kts`, 14 example mains under
`examples/src/main/java/dev/arcp/examples/{cancellation,
capability_negotiation, delegation, extensions, handoff, heartbeats,
human_input, lease_revocation, leases, mcp, permission_challenge,
reasoning_streams, resumability, subscriptions}/`) **must be deleted
in full** before the new tree is added. Per
`02-current-audit.md` "Demolition order" item 6: those 14 directories
implement v2-RFC concepts (`permission_challenge`, `human_input`,
`lease_revocation`, `mcp`, `handoff`, `reasoning_streams`,
`capability_negotiation` with the wrong shape) that do not exist in
v1.1. Salvaging them is more work than rewriting because each
imports the v2-RFC envelope and capability records that Phase 3 / 4
delete. Do not preserve them; do not migrate them; do not "rename
and reshape." Delete the directory tree and the aggregated
`examples/build.gradle.kts`.

The new layout is one Gradle subproject per example (per-row above),
which lets `./gradlew :examples:cancel:run` work without dragging
the other 19 into the daemon's classloader. This matches the TS
suite's one-directory-per-example design and lets reviewers read
exactly one example without scanning siblings.

## CI shape (informational; Phase 7 owns it)

Every example subproject contributes a `run` task that CI invokes:

```
./gradlew :examples:check
```

This aggregate task (registered on the root project) runs
`:examples:<name>:run` for every subproject under `:examples:*`,
each with a 60-second wall-clock timeout (`Test` task on its own
won't do — these are `JavaExec`, so the timeout is enforced by a
`doFirst { timeoutCancellable(60s) }` block on the `JavaExec`
task). Assertions:

1. Exit code 0.
2. `stderr` contains no line matching `Exception|Error:|FATAL`
   (SLF4J's `ERROR` level is the only legitimate `Error:` source
   and examples MUST NOT log at `ERROR` on the success path).
3. Each example's `Main` prints a final line `OK
   <example-name>` to `stdout`; CI greps for it. Missing line =
   silent failure.

The 60-second budget is generous — most examples finish in under 2
seconds in `MemoryTransport` mode. The WebSocket examples are the
ones that approach 10 seconds (`heartbeat` waits for 2 pings at 5s
intervals).

## Anti-slop guardrails (per BOOTSTRAP)

Reviewers reject this document if any of the following appear in any
"Java idiom" cell:

- The words: **leverage, robust, scalable, performant, powerful,
  modern, enterprise-grade, battle-tested, production-ready**.
- A cell that survives a language swap unchanged. "Demonstrates how
  to cancel a job" survives — `cancel` would still read the same in
  Python. The acceptable form is "calls `Thread.interrupt()` on the
  virtual thread inside `StructuredTaskScope`; the scope's
  `joinUntil` unwinds." That cell names a specific JDK type
  (`StructuredTaskScope`), a specific method
  (`joinUntil`), and the unwind mechanism (interrupt).
- A cell that names "showcases streaming" or "demonstrates streams."
  Rejected. The form is "consumes `Flow.Publisher<ResultChunk>`
  via `Flow.Subscriber.onSubscribe(s -> s.request(N))` with N
  driven by sink readiness."

Self-check before merge: every row above names at least one of —
a JDK type (`Flow.Publisher`, `StructuredTaskScope`, `BigDecimal`,
`Instant`, `AtomicLong`, `ConcurrentHashMap`, `FileChannel`,
`SubmissionPublisher`, `ScheduledExecutorService`), an SDK type
(`ArcpClient`, `LeaseGuard`, `BearerVerifier`, `AgentRef`,
`ResumeToken`, `EventDecoder`), or a specific exception
(`PermissionDeniedException`, `BudgetExhaustedException`,
`AgentVersionNotAvailableException`, `LeaseExpiredException`,
`DuplicateKeyException`). Spot check: rows 1, 2, 3, 6, 11, 16, 18
each name ≥ 3 such types. Pass.

## Open questions for Phase 10

- Should `examples/result-chunk/` accept a `--sink=file|bytes|stream`
  flag to demonstrate all three `ResultSink` SPI implementations
  (`02-current-audit.md` §"High-risk items" #2), or stick to
  `FileResultSink` and let `arcp-resume-sqlite`-style opt-in
  examples cover the others? Recommend: file sink only in this
  example, a separate Phase 7 unit test exercises the other two
  sinks. One example, one idea.
- `framework-integration` is one subproject with two `Main` classes
  (Spring Boot and Jakarta). `./gradlew :examples:framework-integration:runSpringBoot`
  vs `:runJakarta`. Phase 10 confirms.
- Tracing example pulls in OpenTelemetry SDK (`io.opentelemetry:
  opentelemetry-sdk`, `opentelemetry-exporter-logging`) as
  `runtimeOnly` deps on the example only — NOT on `arcp-otel`,
  which depends on `opentelemetry-api` (compile) and nothing else.
  Phase 5 confirms the split.
