# 10 — Synthesis

## Executive summary

The Java SDK is not a v1.0 implementation. It implements the
prior, rejected `draft-arcp-01.md` draft ("RFC 0001 v2") and its
public surface — `Envelope`, `Capabilities`, `ErrorCode`, the 21
gRPC-style error enum, the 11 reserved ID types, the
`session.open`/`session.accepted` handshake — does not match
`draft-arcp-02.md` (v1.0) or `draft-arcp-02.1.md` (v1.1). The
v1.1 migration is therefore a controlled demolition: replace the
wire layer, redraw the module split, then add v1.1 features on
top of a freshly-built v1.0 base.

Three things make this affordable:

1. The Java SDK was never published; `CHANGELOG.md` shows only
   pre-release notes. There is no consumer to break (Phase 2).
2. Roughly a quarter of the existing code is reusable plumbing
   (`Ulid`, `MessageId`/`SessionId`/`JobId`/`TraceId`/`SpanId`,
   `StaticBearerValidator`, Spotless+Error Prone+NullAway config,
   JaCoCo wiring). Phase 2 lists what to keep.
3. The TypeScript SDK is v1.1-complete and serves as a 1:1
   reference for surface, examples, and conformance rows
   (Phases 5–8 cite specific TS paths throughout).

Target shape:

- **10 Gradle subprojects**: `arcp-core`, `arcp-client`,
  `arcp-runtime`, `arcp` (umbrella), `arcp-runtime-jetty` (opt-in
  WS server transport), `arcp-otel`, `arcp-tck`,
  `arcp-middleware-jakarta`, `arcp-middleware-spring-boot`,
  `arcp-middleware-vertx`.
- **JDK 21 LTS floor, JDK 25 toolchain**, `options.release.set(21)`
  for bytecode. Virtual threads stable; `StructuredTaskScope`
  used only when `Runtime.version().feature() >= 25` —
  `--enable-preview` is never required of consumers.
- **JSON: Jackson** with `USE_BIG_DECIMAL_FOR_FLOATS=true` and
  `FAIL_ON_UNKNOWN_PROPERTIES=false`. WS client: JDK
  `HttpClient.WebSocket`. WS server: Jetty 12 (opt-in).
- **20 example subprojects** under `:examples` mirroring 22 TS
  examples (`express`/`fastify`/`bun` collapse to one
  `framework-integration`).
- **6 docs diagrams** (`.dot` → `.svg` via `docs/diagrams/Makefile`).
- **Test floor: 87% line AND branch** via JaCoCo, with
  per-package floors raising `runtime.lease`,
  `runtime.budget`, and `core.wire` to 100%. PIT mutation runs
  nightly with a 70% floor on runtime/wire.
- **18 features in `CONFORMANCE.md`**: every v1.1 §§ row keyed
  to a file:line and a `DynamicTest` in `arcp-tck`.

If those four sentences match what we ship, v1.1 is done.

---

## Contradictions resolved across Phases 3–9

Five points where the parallel phase docs disagreed or
underspecified. Resolved here so Phase 10 is the single source of
truth.

### 1. WebSocket server: where does Jetty live?

- Phase 3 (libraries) picks **Jetty 12** for the server in an
  opt-in `arcp-runtime-jetty` module.
- Phase 4 (architecture) lists six subprojects (no `-jetty`) and
  defers the choice behind a `Transport` sealed interface.
- Phase 5 (middleware) introduces `arcp-middleware-jakarta` and
  `arcp-middleware-spring-boot`, both of which run on a Servlet
  container (Tomcat or Jetty).

**Resolution.** Three concerns, three modules:

| Module                          | Owns                                                                            |
| ------------------------------- | ------------------------------------------------------------------------------- |
| `arcp-runtime`                  | The `Transport` sealed interface and `MemoryTransport`. No WS-server code.      |
| `arcp-runtime-jetty`            | A `JettyServerTransport` implementing the SPI by embedding `org.eclipse.jetty.server.Server`. Opt-in. |
| `arcp-middleware-jakarta`       | A Jakarta WebSocket `@ServerEndpoint` adapter for consumers who already run a Servlet container (Tomcat, Undertow, Jetty's Servlet mode). Wraps `ArcpRuntime.accept(transport)`. |

`arcp-runtime` is a library (no I/O). `arcp-runtime-jetty` is the
"batteries-included" server transport for users who do not bring
their own. `arcp-middleware-jakarta` is for users who do. They
coexist; consumers pick exactly one.

### 2. Final subproject count

Phase 4 = 6 subprojects. Phase 5 = +3 middleware. Phase 3 = +1
opt-in Jetty. Phase 8's README packaging table lists 7. The
reconciled total:

| # | Subproject                      | Source phase | Required? |
| - | ------------------------------- | ------------ | --------- |
| 1 | `arcp-core`                     | Phase 4      | Yes       |
| 2 | `arcp-client`                   | Phase 4      | Yes       |
| 3 | `arcp-runtime`                  | Phase 4      | Yes       |
| 4 | `arcp` (umbrella)               | Phase 4      | Yes       |
| 5 | `arcp-runtime-jetty`            | Phase 3      | Yes (default WS server) |
| 6 | `arcp-otel`                     | Phase 4 / 5  | Yes (parity with TS)    |
| 7 | `arcp-tck`                      | Phase 4      | Yes (interop forcing function) |
| 8 | `arcp-middleware-jakarta`       | Phase 5      | Yes       |
| 9 | `arcp-middleware-spring-boot`   | Phase 5      | Yes       |
| 10 | `arcp-middleware-vertx`        | Phase 5      | Yes (Vert.x audience confirmed in Phase 5) |

Phase 8's README packaging table must be updated to 10 rows (it
currently lists 7).

### 3. `StructuredTaskScope` and JDK 21 vs 25

- Phase 3: "JEP 505 only behind a `Runtime.version().feature()
  >= 25` guard; never `--enable-preview` in published artifacts."
- Phase 4: "Recommend defer `StructuredTaskScope` until JDK 25
  is the consumer floor."
- Phase 7: "Property test #2 (`event_seq` monotonicity) uses
  `StructuredTaskScope`."

**Resolution.** Production code in `arcp-runtime` uses **plain
virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`),
which is stable on JDK 21. `StructuredTaskScope` appears only
under a `if (Runtime.version().feature() >= 25)` guarded helper
in `arcp-runtime` — falls back to manual `Future` join on JDK 21.
**Property test #2** runs under both modes via a JUnit 5
`@EnabledForJreRange(min = JRE.JAVA_25)` tag; on JDK 21 CI the
test exercises the manual-join path. No published artifact
requires `--enable-preview`.

### 4. Example count: 18 vs 20 vs 22

- BOOTSTRAP cited 18.
- Phase 6 counted the TS filesystem: 22 directories.
- Phase 8 reported 18 in `docs/04-examples/`.

**Resolution.** Source of truth is the filesystem: **22 TS
example directories collapsed to 20 Java example subprojects**
(Phase 6). Phase 8's `docs/04-examples/` directory holds **20
files**, one per Java example subproject — update Phase 8 doc.

### 5. CONFORMANCE.md vocabulary: drop "Partial"

- The current `CONFORMANCE.md:5–6` uses Implemented / Partial /
  Deferred (and "Not yet wired" once).
- The TS `CONFORMANCE.md` uses only Implemented / Deferred.
- Phase 8 mandates the TS shape.

**Resolution.** "Partial" is banned in the new CONFORMANCE.md.
Each row is either Implemented (with a `path:LineNumber`) or
Deferred (with a one-line rationale). A row that "partially
works" is decomposed into multiple rows — one per requirement
— each binary.

---

## Ordered milestones

Each milestone produces a reviewable PR. Spec § anchors and the
phase doc that scopes the work appear in every row. The
sequencing rule: do not start a milestone whose inputs are still
shifting.

### M0 — Demolition prep (no code shipped)

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 0.1 | Pin spec target: `README.md`, `CONFORMANCE.md` first paragraph                            | `README.md`, `CONFORMANCE.md`                                                                  | (meta) | 8     |
| 0.2 | Delete `RFC-0001-v2.md`                                                                   | (delete)                                                                                       | (meta) | 2     |
| 0.3 | Bump `version = "1.1.0-SNAPSHOT"` in root `build.gradle.kts`; update POM description       | `build.gradle.kts`, `lib/build.gradle.kts:117`                                                 | (meta) | 2     |
| 0.4 | Drop `options.release.set(25)` to 21; keep toolchain at 25                                | `lib/build.gradle.kts:42`                                                                      | (meta) | 3     |
| 0.5 | Prune `gradle/libs.versions.toml` to Phase 3's final block                                | `gradle/libs.versions.toml`                                                                    | (meta) | 3     |

### M1 — Module split

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 1.1 | Create empty Gradle subprojects in `settings.gradle.kts`                                 | `settings.gradle.kts`                                                                          | (meta) | 4     |
| 1.2 | Per-subproject `build.gradle.kts` with `dependencies { api/implementation }` from Phase 4 | `arcp-core/build.gradle.kts`, …, `arcp-middleware-vertx/build.gradle.kts`                       | (meta) | 4     |
| 1.3 | `module-info.java` per subproject with the `opens X to com.fasterxml.jackson.databind`   | `arcp-core/src/main/java/module-info.java`, etc.                                               | (meta) | 4     |
| 1.4 | Move `Ulid`, `MessageId`, `SessionId`, `JobId`, `TraceId`, `SpanId`, `ARCPMapper`, `StaticBearerValidator`, `MemoryTransport` from `:lib` into `arcp-core` | (file moves)                                                                                   | (meta) | 2     |
| 1.5 | Delete `:lib` subproject after evacuation                                                | `settings.gradle.kts`, `lib/`                                                                  | (meta) | 2     |

### M2 — v1.0 wire layer

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 2.1 | New `Envelope` record per Phase 4 §2 (8 fields, `arcp: "1"`)                              | `arcp-core/src/main/java/dev/arcp/core/wire/Envelope.java`                                     | §5.1   | 4     |
| 2.2 | Configure `ArcpMapper`: `USE_BIG_DECIMAL_FOR_FLOATS`, `FAIL_ON_UNKNOWN_PROPERTIES=false`, `JavaTimeModule`, custom `Instant` deserializer rejecting non-`Z` | `arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java`                                   | §5.1, §9.5 | 3 / 4 |
| 2.3 | Sealed `Message permits SessionHello, SessionWelcome, SessionBye, JobSubmit, JobAccepted, JobEvent, JobResult, JobError, JobCancel` (v1.0 set) | `arcp-core/src/main/java/dev/arcp/core/messages/*.java`                                        | §6, §7, §8 | 4     |
| 2.4 | Sealed `EventBody permits LogEvent, ThoughtEvent, ToolCallEvent, ToolResultEvent, StatusEvent, MetricEvent, ArtifactRefEvent, DelegateEvent` (v1.0 set) | `arcp-core/src/main/java/dev/arcp/core/events/*.java`                                          | §8.2   | 4     |
| 2.5 | `ErrorCodes` enum (15-code v1.1 set) + sealed `ArcpException` with `Retryable`/`NonRetryable` split + 15 subclasses | `arcp-core/src/main/java/dev/arcp/core/error/*.java`                                           | §12    | 4     |
| 2.6 | `Capability` enum (Phase 1) + `CapabilityNegotiator.intersect(Set, Set)`                  | `arcp-core/src/main/java/dev/arcp/core/capabilities/*.java`                                    | §6.2   | 1 / 4 |
| 2.7 | Property test #1 (envelope round-trip) green                                              | `arcp-core/src/test/java/dev/arcp/core/property/EnvelopeRoundTripPropertyTest.java`            | §5.1   | 7     |

### M3 — Runtime + client v1.0

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 3.1 | `Transport` sealed interface; `MemoryTransport` (v1.1 wire); `Frame` boundary             | `arcp-core/src/main/java/dev/arcp/core/transport/Transport.java`, `MemoryTransport.java`        | §4     | 4     |
| 3.2 | `ArcpRuntime` session FSM (hello → welcome → active → bye); `SessionContext.nextEventSeq()` atomic counter; resume buffer (in-memory `RingBuffer` per session) | `arcp-runtime/src/main/java/dev/arcp/runtime/session/*.java`                                   | §6.1, §6.2, §6.3, §6.7 | 4     |
| 3.3 | `ArcpRuntime` job FSM (submit → accepted → events → result/error); cancellation via `Thread.interrupt()` | `arcp-runtime/src/main/java/dev/arcp/runtime/job/*.java`                                       | §7.1, §7.3, §7.4 | 4     |
| 3.4 | Idempotency store (in-memory `ConcurrentHashMap<(Principal, IdempotencyKey), JobId>` with 24h TTL sweep) | `arcp-runtime/src/main/java/dev/arcp/runtime/idempotency/IdempotencyStore.java`                | §7.2   | 4 / 6 |
| 3.5 | Lease enforcement (subset check, lease grammar)                                          | `arcp-runtime/src/main/java/dev/arcp/runtime/lease/*.java`                                     | §9.1–§9.4 | 4     |
| 3.6 | Delegation                                                                                | `arcp-runtime/src/main/java/dev/arcp/runtime/delegate/*.java`                                  | §10    | 4     |
| 3.7 | `ArcpClient` connect / submit / cancel; `JobHandle.result(): CompletableFuture<JobResult>` | `arcp-client/src/main/java/dev/arcp/client/*.java`                                             | §6.1, §7.1, §7.4 | 4     |
| 3.8 | `arcp-runtime-jetty`: `JettyServerTransport` embedding `org.eclipse.jetty.server.Server` with virtual-thread executor | `arcp-runtime-jetty/src/main/java/dev/arcp/runtime/jetty/*.java`                               | §4.1   | 3     |
| 3.9 | Integration test over `MemoryTransport`; integration test over Jetty loopback           | `arcp-sdk/src/test/.../integration/`, `arcp-sdk/src/test/.../e2e/`                            | §13 (v1.0 flows) | 7     |

### M4 — v1.1 additions

Order within M4 is mostly free; the dependency graph is sparse.
Recommended order:

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 4.1 | `Feature` enum; `features` array on hello/welcome; intersection                            | `arcp-core/src/main/java/dev/arcp/core/capabilities/Feature.java`                              | §6.2   | 1 / 4 |
| 4.2 | Heartbeats: `session.ping`/`session.pong`; `ScheduledExecutorService` per runtime; `HEARTBEAT_LOST` after 2 missed intervals | `arcp-runtime/src/main/java/dev/arcp/runtime/heartbeat/*.java`                                 | §6.4   | 1 / 4 |
| 4.3 | Event ack: `session.ack`; `AtomicLong lastProcessedSeq`; back-pressure status event       | `arcp-runtime/src/main/java/dev/arcp/runtime/ack/*.java`                                       | §6.5   | 1     |
| 4.4 | Job listing: `session.list_jobs`/`session.jobs`; `Page<JobSummary>`; per-Principal scope  | `arcp-client/src/main/java/dev/arcp/client/ArcpClient.java` (listJobs method), `arcp-runtime/src/main/java/dev/arcp/runtime/listing/*.java` | §6.6   | 1     |
| 4.5 | Agent versioning: `AgentRef.parse("name@version")`; `AgentRegistry.resolve(AgentRef)`; rich `agents:` shape on welcome | `arcp-core/src/main/java/dev/arcp/core/agents/AgentRef.java`, `arcp-runtime/.../agents/AgentRegistry.java` | §6.2, §7.5 | 1     |
| 4.6 | Job subscription: `job.subscribe`/`job.subscribed`/`job.unsubscribe`; `Flow.Publisher<Event>` via `SubmissionPublisher`; drop-on-overflow | `arcp-client/src/main/java/dev/arcp/client/ArcpClient.java` (subscribe), `arcp-runtime/.../subscribe/*.java` | §7.6   | 1 / 4 |
| 4.7 | `ProgressEvent` body record (`current` ≥ 0, optional `total`) added to sealed `EventBody` | `arcp-core/.../events/ProgressEvent.java`                                                      | §8.2.1 | 1     |
| 4.8 | `result_chunk` streaming: chunk encoder; `ResultStream`, `ResultSink` SPI; `FileResultSink` writes via `FileChannel.write` | `arcp-client/src/main/java/dev/arcp/client/results/*.java`, `arcp-runtime/.../result/*.java`   | §8.4   | 1 / 4 |
| 4.9 | Lease expiration: `LeaseConstraints.expiresAt`; `Instant` parser rejects non-`Z`; watchdog via `ScheduledExecutorService.schedule(..., until)` | `arcp-core/.../lease/LeaseConstraints.java`, `arcp-runtime/.../lease/ExpirationWatchdog.java`  | §9.5   | 1 / 4 |
| 4.10 | Budget: per-currency `ConcurrentHashMap<String, AtomicReference<BigDecimal>>`; CAS decrement; `BUDGET_EXHAUSTED` as `tool_result` error | `arcp-runtime/.../budget/BudgetCounters.java`                                                  | §9.6   | 1 / 4 |
| 4.11 | Three new error codes wired into `ErrorCodes` + sealed exceptions (`AgentVersionNotAvailableException`, `LeaseExpiredException`, `BudgetExhaustedException` — all non-retryable) | `arcp-core/.../error/*.java`                                                                    | §12    | 1 / 4 |
| 4.12 | Delegation subset rules extended (child budget ≤ parent remaining; child expires_at ≤ parent) | `arcp-runtime/.../delegate/*.java`                                                              | §9.4   | 1     |

### M5 — Adapters

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 5.1 | `arcp-otel`: W3C `traceparent` propagation; span per envelope; `arcp.lease.expires_at`, `arcp.budget.remaining` attributes | `arcp-otel/src/main/java/dev/arcp/otel/*.java`                                                  | §11    | 5     |
| 5.2 | `arcp-middleware-jakarta`: `@ServerEndpoint`; Host-header allowlist; `Principal` injection from Jakarta Authentication | `arcp-middleware-jakarta/src/main/java/dev/arcp/middleware/jakarta/*.java`                      | §4.1, §14 | 5     |
| 5.3 | `arcp-middleware-spring-boot`: WebMvc/Tomcat; `ArcpRuntime` `@Bean`; Host-header `Filter`  | `arcp-middleware-spring-boot/src/main/java/dev/arcp/middleware/spring/*.java`                   | §4.1, §14 | 5     |
| 5.4 | `arcp-middleware-vertx`: `HttpServer.webSocketHandler(ServerWebSocket)`                   | `arcp-middleware-vertx/src/main/java/dev/arcp/middleware/vertx/*.java`                          | §4.1, §14 | 5     |

### M6 — Tests, examples, docs (in parallel)

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 6.1 | Property tests #2–#6 + cancellation tests + v1.0/v1.1 compatibility matrix in `arcp-tck`  | `arcp-tck/src/test/.../`                                                                       | §8.3, §7.2, §9.4, §9.6, §8.4 | 7 |
| 6.2 | PIT mutation job (nightly); per-package JaCoCo floors                                     | `lib/build.gradle.kts` per-module, CI workflow                                                  | (test) | 7     |
| 6.3 | nginx pass-through E2E test                                                              | `arcp-sdk/src/test/.../e2e/NginxPassThroughE2ETest.java`                                       | §4.1   | 7     |
| 6.4 | 20 example subprojects per Phase 6; delete 14 existing examples                          | `examples/{submit-and-stream,…,framework-integration}/`                                         | §13    | 6     |
| 6.5 | `docs/` tree per Phase 8; Javadoc on every public type; CONFORMANCE.md rewritten         | `docs/00-overview.md` … `docs/06-conformance.md`, `CONFORMANCE.md`, `README.md`                | (docs) | 8     |
| 6.6 | 6 diagrams: `module-graph.dot`, `session-lifecycle.dot`, `job-lifecycle.dot`, `capability-negotiation.dot`, `heartbeat-ack.dot`, `result-chunk.dot` + `Makefile` | `docs/diagrams/*.dot`, `docs/diagrams/Makefile`                                                | (docs) | 9     |

### M7 — Release prep

| # | Work                                                                                       | Files                                                                                          | Spec § | Phase |
| - | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | ------ | ----- |
| 7.1 | `CHANGELOG.md` 1.1.0 entry                                                                | `CHANGELOG.md`                                                                                 | (meta) | 8     |
| 7.2 | CI matrix: JDK 21 + 25 on Linux; nginx E2E enabled; PIT nightly enabled                   | `.github/workflows/*.yml`                                                                       | (meta) | 7     |
| 7.3 | Tag `1.1.0`; publish to Maven Central                                                    | (release)                                                                                       | (meta) | —     |

---

## Risks

Concrete, Java-specific. Generic risks (`security`, `complexity`)
are not on this list.

1. **`SubmissionPublisher` carrier-thread pinning under heavy
   subscribe fan-out.** `SubmissionPublisher.submit()` blocks the
   caller when subscribers fall behind. If the WebSocket reader
   loop runs on a platform thread, that block pins the carrier.
   Mitigation (Phase 4): the WS reader is itself a virtual
   thread; the `submit()` block parks the virtual thread cheaply.
   Test (Phase 7 cancellation #3) covers the case.
2. **`--release 21` skew.** Setting toolchain to 25 without
   `--release 21` produces JDK 25 bytecode and quietly breaks
   JDK 21 consumers. The CI matrix (JDK 21 + 25) catches this,
   but only if the JDK 21 row actually loads the published JAR
   — not just runs unit tests with the toolchain JDK. Make the
   JDK 21 CI row consume the assembled JAR.
3. **JPMS opens are repeated per module.** Every module containing
   records must `opens X to com.fasterxml.jackson.databind`. Easy
   to miss when adding a new package. Mitigation: a `core` JUnit
   test enumerates every record type via reflection and asserts
   Jackson can instantiate it; missing `opens` fails the test
   with a clear message.
4. **`BigDecimal` budget math via Jackson default-double.** If
   `USE_BIG_DECIMAL_FOR_FLOATS=true` is not set on the live
   `ObjectMapper`, budget arithmetic silently uses `double` and
   `0.1 + 0.2 != 0.3` returns wrong remaining. Mitigation:
   property test #5 (Phase 7) catches it. Also: a Jackson feature
   assertion in a `core` test verifies the mapper config.
5. **Spotless Palantir vs Eclipse drift.** Eclipse JDT format
   differs from Palantir on multi-line generic boundaries and
   record canonical-constructor formatting. When Palantir 2.69+
   ships with JDK 25 support, switching back will re-format the
   entire codebase. Mitigation: pick Eclipse now, defer the
   re-format to a single-PR mass change after the v1.1 release.
6. **Subscribe authority confusion.** §7.6 mandates that
   subscribers do not carry cancel authority. A naive
   implementation that exposes `JobHandle.cancel()` from a
   `subscribe()` result violates this. Mitigation: `subscribe()`
   returns `Flow.Publisher<Event>`, not `JobHandle`. Only
   `submit()` returns `JobHandle`. The compiler enforces it.
7. **Jetty 12 toolchain version drift.** Jetty 12.0.x targets
   JDK 17+; future Jetty majors may bump. If Jetty 13 drops JDK
   21, `arcp-runtime-jetty` is stranded. Mitigation: pin Jetty
   minor; document the version policy in `docs/05-reference/`.

---

## Non-goals (intentional, restated)

From `01-spec-delta.md` (spec's "Not in v1.1") and Phase 4 / 8:

- **No job pause/unpause** (spec's deferred list).
- **No job priority or scheduling hints.**
- **No federation across runtimes.**
- **No LLM-token streaming surface** (distinct from
  `result_chunk`; `result_chunk` is bytes, not tokens).
- **No CLI in v1.1 first cut.** The existing `cli/` skeleton
  stays out of the public-artifact set. Phase 10 / M7 may revisit
  if reviewers want it; default is defer.
- **No persistent idempotency or resume store.** In-memory
  `ConcurrentHashMap` + ring buffer match TS; persistence is a
  consumer concern.
- **No migration guide from v0.1-WIP.** That SDK was never
  published.
- **No Helidon SE or Quarkus middleware in v1.1 first cut**
  (Phase 5).
- **No `--enable-preview` requirement on consumers** (Phase 3/4).
- **No JDK 17 support** (Phase 3 floor is 21).
- **No mTLS or OAuth2 auth schemes** (spec defines only Bearer
  for v1.0/v1.1; existing `JwtValidator` moves to an opt-in
  `arcp-auth-jwt` module if it survives Phase 3).

---

## Open questions for the user

1. **CLI scope.** The existing `cli/ArcpCli.java` is a 1-file
   skeleton. Ship a renamed `arcp-cli` (10th subproject becomes
   11th, no v1.1 features) for v1.1, or defer? Recommendation:
   defer; CLI is not in TS and not in the v1.1 spec.

2. **`arcp-tck` publication.** Phase 4 recommends shipping
   `arcp-tck` as a Maven artifact so downstream implementations
   can consume the JUnit `@TestFactory` abstract classes. Is
   there a known downstream implementor (the Kotlin SDK? Other
   JVM languages?) who would consume it on day one, or is this
   speculative? Recommendation: ship it anyway; cost is small and
   absence makes interop harder later.

3. **`arcp-middleware-vertx`.** Phase 5 accepted Vert.x; Phase 10
   ranks it lowest of the four adapters. Defer to a v1.1.1
   minor? Recommendation: ship in 1.1.0 if M5 stays on the
   critical path budget; otherwise cut last.

4. **`arcp-runtime-jetty` as default vs. opt-in.** Phase 3 marks
   it opt-in. Phase 8's README "Quickstart" needs to demonstrate
   a server in 60 lines, which requires a WS server transport.
   Should the umbrella `arcp` artifact transitively depend on
   `arcp-runtime-jetty` for the convenience case, or keep the
   umbrella server-less and require an explicit pick?
   Recommendation: umbrella depends on `arcp-runtime-jetty`;
   consumers who want a different WS server depend on
   `arcp-client` + `arcp-runtime` directly.

5. **Resume buffer persistence interface.** §6.3 says the buffer
   is in-memory in TS. A `ResumeStore` SPI would let consumers
   plug in Redis / SQLite without forking the runtime. Worth the
   surface, or YAGNI? Recommendation: YAGNI for 1.1.0; add the
   SPI in 1.1.1 if asked.

6. **Spec change observation: §13 examples were renumbered in
   v1.1.** The v1.0 examples (`draft-arcp-02.md`) renumbered as
   v1.1 §13.1–§13.7 ("Heartbeat Liveness", "Event Acknowledgement
   and Slow Consumer", …). The Java conformance harness should
   key rows to v1.1's numbering. Confirm.

---

## Critical-path estimate

Not a schedule. A relative-effort estimate for the reviewer to
sanity-check the milestone breakdown.

| Milestone                             | Relative effort | Critical-path? |
| ------------------------------------- | --------------- | -------------- |
| M0 demolition prep                   | 0.25 day        | Yes            |
| M1 module split                       | 1 day           | Yes            |
| M2 v1.0 wire layer                   | 4 days          | Yes            |
| M3 v1.0 runtime + client             | 8 days          | Yes            |
| M4 v1.1 additions                    | 6 days          | Yes (12 sub-PRs, parallelizable in pairs) |
| M5 adapters                           | 5 days          | Partial — `arcp-otel` blocks `tracing` example |
| M6 tests / examples / docs           | 10 days         | Parallel; staffed by 2–3 reviewers |
| M7 release prep                       | 0.5 day         | Yes            |

Total critical path roughly **5 weeks single-engineer**, **2.5
weeks** with a second engineer taking M6 in parallel after M3.
The wire rewrite (M2) cannot start until the spec target is
pinned (M0) and the modules exist (M1). After M3, v1.1 features
(M4) and adapters (M5) parallelize.

---

## Reading-order summary for new reviewers

For a reviewer dropping in cold:

1. `BOOTSTRAP.md` — operating rules.
2. `01-spec-delta.md` — what changed in v1.1.
3. `02-current-audit.md` — what we have and why we're tearing
   most of it down (headline: SDK targets the wrong draft).
4. This file (`10-synthesis.md`).
5. Phases 3–9 as reference, on demand.

The other nine planning files are dense but self-contained; this
file is the index.
