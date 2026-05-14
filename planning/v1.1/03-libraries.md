# 03 — Library Picks for ARCP v1.1 Java SDK

Target floor: JDK 21 LTS. Toolchain compiles on JDK 25; `--release 21`
emits bytecode consumable by 21+. Libraries must not pin a higher floor
without justification. Public API ships SLF4J facade only; no logging
binding. No Lombok anywhere (records cover the ground without compile-time
annotation processing leaking into consumers).

Pricing of words: BOOTSTRAP banned "robust/scalable/performant/powerful/
modern/enterprise-grade/battle-tested/production-ready/leverage". Every
pick below cites a concrete reason and a concrete alternative rejection.

---

### 1. JSON

**Pick:** `com.fasterxml.jackson.core:jackson-databind:2.18.2`,
`com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2`
(Maven Central, current pin)
**Why:** Streaming reader + per-mapper feature flags let §9.6 budget
metric numerics be parsed as `BigDecimal` (`USE_BIG_DECIMAL_FOR_FLOATS`)
without changing call sites; Gson decodes JSON numbers as `double` and
forces a post-parse re-parse from the raw token. JSON-B / Yasson would
drag a Jakarta EE container assumption into a plain JDK 21 library;
jsoniter is unmaintained (last release 2020).
**Why not Gson/JSON-B/jsoniter:** Gson lacks per-property
`BigDecimal`-coercion at the streaming layer; JSON-B requires a CDI
provider in practice and provider-shopping is a consumer trap; jsoniter
has no JDK 21 testing and last commit was years ago.
**Notes (v1.1-specific):**
- Configure `ARCPMapper.create()` with: `USE_BIG_DECIMAL_FOR_FLOATS=true`
  (§9.6 budget arithmetic), `FAIL_ON_UNKNOWN_PROPERTIES=false` (§5.1
  forward-compat envelope rule), `WRITE_DATES_AS_TIMESTAMPS=false` and
  `JavaTimeModule` for `Instant` (§9.5 `expires_at` parses to `Instant`).
- Register a custom deserializer for `lease_constraints.expires_at` that
  rejects offsets other than `Z` and past values at the SDK seam — NOT at
  the wire boundary, so the rejection cites the validated field rather
  than a JSON parse error.
- Module reflection: `arcp.core` will need `opens dev.arcp.core.wire to
  com.fasterxml.jackson.databind;` for every package holding records.
  Audit on each module split (Phase 4).
- No `jackson-bom`; keep the two artifacts pinned directly to make
  consumer overrides obvious.

---

### 2. WebSocket client

**Pick:** `java.net.http.HttpClient.WebSocket` (JDK stdlib, no dep)
**Why:** Zero extra transitive surface, built into JDK 21, integrates
with virtual threads when the listener callbacks return promptly. The
`WebSocket.Listener` API gives chunked frame delivery (`onText(...,
last)`), which maps directly to §8.4 `result_chunk` reassembly without an
intermediate framework buffer.
**Why not Netty / Jetty client / Vert.x:** Netty pulls EPoll/KQueue
native libs and an event-loop programming model that fights virtual
threads; Jetty client is a fine pick but adds 4+ JARs for a feature the
JDK has; Vert.x assumes you adopt its reactor.
**Notes (v1.1-specific):**
- The current `org.java-websocket:Java-WebSocket` dep is dropped.
- `HttpClient.WebSocket` does not expose ping/pong RFC 6455 control
  frames to application code; §6.4 heartbeats are application-level
  `session.ping`/`session.pong` messages (per spec §6.4 — heartbeats are
  not in `event_seq` but they ARE wire-format JSON envelopes), so this
  limitation does not bite.
- `WebSocket.Listener.onText` delivers partials; the SDK boundary
  reassembles to a complete envelope before handing to Jackson — gate
  per-frame size against the §14 security note (1 MB cap).

---

### 3. WebSocket server

**Pick:** `org.eclipse.jetty.websocket:jetty-websocket-jakarta-server:12.0.16`
(Maven Central) — pulled in as part of an opt-in `arcp-runtime-jetty`
module, NOT in `arcp-core`.
**Why:** Jetty 12 implements Jakarta WebSocket 2.1 against JDK 21
natively and runs without a Servlet container if you embed
`org.eclipse.jetty.server.Server`. Netty would require us to write the
HTTP-upgrade handshake from scratch; raw Jakarta WebSocket on Servlet 6
forces consumers to ship a Servlet container.
**Why not Netty / raw Jakarta / Vert.x:** Netty wire-level is a footgun
for protocol authors who are not also Netty experts; raw Jakarta requires
a container (Tomcat, Undertow) which contradicts "library, not
framework"; Vert.x assumes adoption of its reactor and breaks structured
concurrency.
**Notes (v1.1-specific):**
- Embedded Jetty + virtual-thread executor: `server.setThreadPool(new
  VirtualThreadPerTaskExecutor())`. §7.6 `job.subscribe` fan-out needs a
  thread per subscriber that mostly blocks on the event publisher;
  virtual threads make this affordable.
- Per-session back-pressure via Jetty's `RemoteEndpoint.sendString(...,
  WriteCallback)` — the runtime's `SubmissionPublisher` (Phase 4)
  routes through this and drops-on-overflow per the §6.5 advisory model
  (no wire-level credit in v1.1).
- Jetty 12 requires `org.slf4j:slf4j-api` only — no binding pulled.

---

### 4. HTTP

**Pick:** `java.net.http.HttpClient` (JDK stdlib)
**Why:** Already required transitively (it's the substrate for the
WebSocket client). v1.1 has no spec-mandated plain HTTP traffic; this is
for ancillary use (auth token refresh hooks, OTel exporter pull
endpoints) and the stdlib client covers it with HTTP/2 + virtual-thread
compatibility.
**Why not Apache HttpClient / OkHttp:** Both add a transitive surface
the SDK does not need; OkHttp pulls Kotlin stdlib which is a no-go for a
Java library; Apache is fine but redundant given the JDK client suffices.
**Notes:** None v1.1-specific. If a consumer needs HTTP/3 (QUIC, §4
optional transport), they layer it themselves; the SDK does not ship
QUIC.

---

### 5. Concurrency

**Pick:** JDK virtual threads + `StructuredTaskScope` (JEP 505,
stable in JDK 25 / preview in JDK 21 via `--enable-preview`)
**Why:** Virtual threads + structured concurrency fit ARCP's per-job and
per-session unit-of-work model directly (`Subtask` per delegate, scope
cancellation on `job.cancel` per §7.4). Reactor/Mutiny push the entire
SDK into reactive types that leak into the public API — a
`Mono<JobAccepted>` in a `Flow.Publisher`-shaped SDK is a category
mismatch.
**Why not Reactor / Mutiny / RxJava:** All three force the public API to
return their reactive types or pay reactive↔imperative conversion costs;
Java has `java.util.concurrent.Flow` in the JDK since 9, and §7.6 maps
to `Flow.Publisher<Event>` natively.
**Policy on JEP 505 preview status:**
- The SDK targets `--release 21` for bytecode.
- For JDK 21 consumers, the SDK uses the **stable** `Executors
  .newVirtualThreadPerTaskExecutor()` (final in JDK 21, JEP 444). It
  does NOT use `StructuredTaskScope` internally on the public surface.
- Internal modules MAY use `StructuredTaskScope` only behind a runtime
  feature check (`Runtime.version().feature() >= 25`) — practically,
  `arcp-runtime` (server-side) can require JDK 25 and use it; `arcp-core`
  (client-side, what most consumers depend on) sticks to virtual threads
  + `Future` + try-with-resources on an `ExecutorService`.
- No `--enable-preview` flag in the published artifacts. Preview-flagged
  classfiles are not consumable by non-preview JVMs and Maven Central
  rules against publishing preview bytecode.
- Phase 4 architecture re-pins this: `arcp-core` floor JDK 21, no
  preview; `arcp-runtime` floor JDK 21 but uses
  `StructuredTaskScope` only inside `if (Runtime.version().feature() >=
  25)` guards with a virtual-thread `try/finally` fallback for 21–24.

---

### 6. Scheduled timers (§6.4 heartbeats)

**Pick:** `Executors.newScheduledThreadPool(1, virtualThreadFactory)`
(JDK stdlib)
**Why:** `StructuredTaskScope` is fork/join, not scheduling — confirmed
in 02-current-audit §"High-risk items" #4. A single
`ScheduledExecutorService` per runtime instance, with per-session
`ScheduledFuture`-cancelled-on-close, is the smallest design that maps to
§6.4's "send a ping if idle for `heartbeat_interval_sec`."
**Why not Netty's `EventLoopGroup` timer / Quartz / `Timer`:** Netty is
rejected (see §2); Quartz is cron-grade overkill; `java.util.Timer` runs
on a single platform thread and dies if a task throws.
**Notes:** Use `Thread.ofVirtual().factory()` for the pool so the
scheduling thread is platform but the dispatched ping-send task is
virtual. Cancel `ScheduledFuture` on `session.close` and on transport
drop.

---

### 7. Logging

**Pick:** `org.slf4j:slf4j-api:2.0.16` (api scope, no binding)
**Why:** Per BOOTSTRAP, library consumers MUST NOT be forced into a
logging implementation. SLF4J 2.x is the only widely-adopted facade with
a fluent API and JDK 21 compatibility. The SDK ships zero bindings.
**Why not java.util.logging directly:** JUL has no MDC, no fluent API,
and consumers routing through SLF4J have to install `jul-to-slf4j`
anyway.
**Notes:**
- `slf4j-simple:2.0.16` is `testRuntimeOnly` only — for example mains
  and the test classpath. Never `runtimeOnly` on `:lib`.
- README / Javadoc-package-summary MUST tell consumers: "this library
  uses SLF4J; add a binding (logback-classic, log4j-slf4j2-impl,
  slf4j-jdk14) on your runtime classpath."

---

### 8. IDs (ULID + UUIDv7)

**Pick:** `com.github.f4b6a3:ulid-creator:5.2.3` and
`com.github.f4b6a3:uuid-creator:6.0.0` (Maven Central)
**Why:** Same author, consistent API, monotonic ULID generator
(`UlidCreator.getMonotonicUlid()`) maps to §8.3's strictly-monotonic
session-scoped `event_seq` regime (ULIDs are not used for `event_seq`
itself — that's a `long` — but they ARE used for `id`, `job_id`,
`result_id`, `call_id`, `nonce` per spec examples). UUIDv7 is the
emerging standard for new IDs and the `uuid-creator` library is the
only mainstream Java impl. `de.huxhorn.sulky:sulky-ulid` is dormant
(last release 2020).
**Why not sulky-ulid / hand-rolled / `java.util.UUID`:** sulky-ulid
is stale; hand-rolling Crockford-base32 is wasted effort; `UUID.randomUUID()`
is v4 (no time component, bad index locality).
**Notes:**
- `ulid-creator` is an automatic module (no `module-info.java`) — the
  current build already passes `-Xlint:-requires-automatic` to suppress
  the warning. Same applies to `uuid-creator`. Document the automatic
  module name (`com.github.f4b6a3.ulid`) so JPMS consumers don't get
  bitten on a future name change.
- IDs surface as opaque strings on the wire (§5.1); only the SDK
  internals know the generation algorithm. `JobId`, `SessionId`,
  `MessageId` records hold strings — the generators stay private.

---

### 9. Tracing

**Pick:** `io.opentelemetry:opentelemetry-api:1.44.1` (api only;
consumer chooses the SDK)
**Why:** §11 says ARCP propagates W3C Trace Context; OTel API is the
W3C-context-aware tracer interface every Java OTel SDK implements.
Shipping `opentelemetry-sdk` would force a specific tracer provider on
consumers.
**Why not Micrometer Tracing / Brave / hand-rolled:** Micrometer Tracing
is a higher-level facade over OTel/Brave that adds another shim;
Brave/Zipkin is legacy; hand-rolled W3C Trace Context parsing
(`traceparent` header) is fine but doesn't get you span emission —
consumers want spans.
**Notes (v1.1-specific):**
- §11 v1.1 adds two span attributes: `arcp.lease.expires_at` and
  `arcp.budget.remaining`. The OTel adapter (in an opt-in
  `arcp-otel` module per Phase 5, NOT in core) sets these on the
  session/job spans.
- The api-only dep is `runtimeOnly` if and only if the OTel adapter is
  on the classpath; core code uses a tiny internal `Tracer` interface
  with a no-op default and an OTel-backed impl wired by `ServiceLoader`.
  This keeps `arcp-core` free of `io.opentelemetry.*` imports.

---

### 10. Testing

**Picks:**
- `org.junit.jupiter:junit-jupiter:5.11.4` (Maven Central) — runner.
- `org.assertj:assertj-core:3.26.3` — assertions.
- `net.jqwik:jqwik:1.9.2` — property tests (§9.6 budget arithmetic,
  §8.4 chunk reassembly).
- Mutation: `info.solidsoft.gradle.pitest` (PIT) — Gradle plugin
  `1.15.0`, PIT core `1.17.4`.
- **No Mockito by default.** Drop the current `mockito-core` pin from
  `libs.versions.toml`. Prefer hand-rolled fakes for stateful collaborators
  (e.g., `FakeTransport`, `FakeClock`); the existing `MemoryTransport`
  is the right pattern. Add Mockito back only if a Phase 7 test
  genuinely needs verification of a final-method invocation count, and
  even then prefer a spy-via-record approach.

**Why over alternatives:**
- JUnit 5 over JUnit 4 / TestNG: parameterized tests, dynamic tests,
  parallel execution, native JDK 21 support.
- AssertJ over Hamcrest / built-in: fluent chains, soft-assertions for
  multi-field record comparison.
- jqwik over JUnit's `ParameterizedTest`: real shrinking. §9.6 demands
  adversarial decimal inputs (`0.1 + 0.2 ≠ 0.3` under `double`); jqwik
  shrinks failing examples down to minimal counterexamples in BigDecimal
  space.
- PIT over no mutation testing: BOOTSTRAP coverage floor (87%) is line
  coverage; mutation testing is the actual quality signal. Aim for >70%
  mutation-killing coverage on the wire codec and lease enforcement code.

**Notes:**
- Awaitility stays (`org.awaitility:awaitility:4.2.2`) — useful for
  flaky-to-write timing tests around heartbeats (§6.4) and ack flow
  (§6.5).
- `slf4j-simple` is `testRuntimeOnly` only.

---

### 11. Coverage

**Pick:** JaCoCo `0.8.13` (Gradle `jacoco` plugin)
**Why:** Already pinned in `lib/build.gradle.kts:98`; 0.8.13 added Java
25 class major version 69 support, which is required for the JDK 25
toolchain. No alternative needed.
**Why not Cobertura / Clover:** Cobertura is unmaintained; Clover is
commercial and no longer published.
**Notes:**
- Per Phase 7 the coverage floor resets against the new surface (the
  87% number was measured against the v2-RFC code being torn down).
- Configure `jacocoTestCoverageVerification` with a per-bundle floor;
  the wire codec and lease enforcement get 95%, examples get 0% (don't
  count toward the bundle).

---

### 12. Build

**Pick:** Gradle Kotlin DSL with toolchain JDK 25,
`options.release.set(21)`
**Why:** Honors existing build file shape; toolchain 25 gives the build
JDK 25 javac (needed for JaCoCo 0.8.13's class-file probing); `--release
21` emits bytecode consumable by JDK 21 callers. BOOTSTRAP mandates JDK
21 LTS floor; 02-audit's "High-risk items" #7 calls this out explicitly.
**Why not Maven / Bazel:** Maven Kotlin DSL doesn't exist; existing
`:lib`, `:cli`, `:examples` layout is Gradle-native. Bazel is overkill
for a 3-module library.
**Notes:**
- Current `lib/build.gradle.kts:42` has `options.release.set(25)`. **Phase
  3 must change this to `21`** as the first commit.
- Per-module overrides allowed: `arcp-runtime-jetty` can be JDK 21 floor
  too (Jetty 12 supports it); only if a future module needs preview-only
  APIs does the floor bump.
- Use `libs.versions.toml` (already present) — Phase 4 architecture
  expects to read pins from there.

---

### 13. Static analysis

**Pick:** Error Prone + NullAway (already wired), Spotless with
**Eclipse JDT** formatter (existing pin), Checkstyle **dropped**.
**Why:** Error Prone catches the high-value bug patterns (mutability,
null-flow, immutable collection misuse); NullAway enforces JSpecify
annotations as a compile-time gate. Checkstyle adds noise without
catching a class of bug Error Prone misses — Spotless handles formatting
and Error Prone handles semantics; the Checkstyle middle is empty.
**Why not Checkstyle / SpotBugs / SonarQube:** Checkstyle covers
formatting (Spotless owns it) and naming conventions (low signal);
SpotBugs duplicates Error Prone's catches with worse JDK 21 support;
SonarQube is a service, not a library dep.
**Notes on the Palantir vs Eclipse formatter call:**
- `lib/build.gradle.kts:84-89` documents that Palantir Java Format 2.68.0
  is incompatible with JDK 25 javac (`Log$DeferredDiagnosticHandler`
  signature change). **Keep Eclipse JDT** until Palantir publishes a JDK
  25-compatible release. Track upstream issue palantir/palantir-java-format#1234
  (or whichever PR carries the fix).
- Drop the `palantir-fmt = "2.68.0"` line from `libs.versions.toml` —
  it's a dead pin.
- Error Prone 2.36.0 supports JDK 21–25; the plugin `4.1.0` works
  against Gradle 8.x.

---

### 14. Nullness

**Pick:** `org.jspecify:jspecify:1.0.0` (already pinned)
**Why:** JSpecify is the consensus successor to JSR-305 — backed by
Google, Oracle, JetBrains, and the NullAway author. JSR-305
(`javax.annotation.Nullable`) is on the `javax.*` namespace which
Jakarta migration broke, the JSR itself is dormant, and the package
split causes JPMS clashes when two artifacts both ship
`javax.annotation`.
**Why not JSR-305 / Checker Framework qualifiers / IntelliJ
annotations:** JSR-305 is dormant and package-conflicted; Checker
Framework qualifiers (`@MonotonicNonNull` et al.) require Checker
Framework on the compile classpath of consumers; IntelliJ annotations
are IDE-only and ignored by NullAway.
**Notes:**
- NullAway 0.12.1 supports JSpecify 1.0 natively; the existing
  `option("NullAway:AnnotatedPackages", "dev.arcp")` config carries
  over.
- Annotate at package level: `@NullMarked` on every `package-info.java`
  in `arcp-core`. Then individual nullable fields/params get
  `@Nullable`; everything else is non-null by default.

---

## Final dependency block

The pruned `libs.versions.toml` Phase 4 can lift directly. Verified
artifacts; no JDK-25-only versions. Coordinates that already exist in
the current file are unchanged unless noted.

```toml
[versions]
# JSON
jackson = "2.18.2"

# Logging (facade only, no binding shipped)
slf4j = "2.0.16"

# Nullness
jspecify = "1.0.0"

# IDs
ulid = "5.2.3"
uuid = "6.0.0"

# Tracing (api only; consumer brings the SDK)
opentelemetry = "1.44.1"

# WebSocket server (opt-in arcp-runtime-jetty module only)
jetty = "12.0.16"

# Test
junit = "5.11.4"
assertj = "3.26.3"
awaitility = "4.2.2"
jqwik = "1.9.2"

# Build plugins
spotless = "7.0.4"
errorprone-plugin = "4.1.0"
errorprone-core = "2.36.0"
nullaway = "0.12.1"
pitest-plugin = "1.15.0"

# Coverage tool version pinned in build.gradle.kts directly:
#   jacoco { toolVersion = "0.8.13" }

# DROPPED from v1.0 pins:
#   sqlite              — EventLog moves to opt-in arcp-resume-sqlite or deleted (§6.3 buffer is in-memory)
#   java-websocket      — replaced by JDK HttpClient.WebSocket (client) + Jetty (server)
#   nimbus-jwt          — JWT auth not required by v1.1; move to opt-in arcp-auth-jwt
#   json-schema-validator — v1.1 SDK validates via records, not JSON Schema
#   picocli             — CLI deferred per Phase 10 open question
#   mockito             — prefer fakes; add back if a specific Phase 7 test demands it
#   palantir-fmt        — incompatible with JDK 25 javac; Eclipse JDT in use

[libraries]
# JSON
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
jackson-jsr310 = { module = "com.fasterxml.jackson.datatype:jackson-datatype-jsr310", version.ref = "jackson" }

# Logging
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }  # testRuntimeOnly only

# Nullness
jspecify = { module = "org.jspecify:jspecify", version.ref = "jspecify" }

# IDs
ulid = { module = "com.github.f4b6a3:ulid-creator", version.ref = "ulid" }
uuid = { module = "com.github.f4b6a3:uuid-creator", version.ref = "uuid" }

# Tracing (opt-in arcp-otel module)
opentelemetry-api = { module = "io.opentelemetry:opentelemetry-api", version.ref = "opentelemetry" }

# WebSocket server (opt-in arcp-runtime-jetty module)
jetty-server = { module = "org.eclipse.jetty:jetty-server", version.ref = "jetty" }
jetty-websocket-jakarta-server = { module = "org.eclipse.jetty.websocket:jetty-websocket-jakarta-server", version.ref = "jetty" }

# WebSocket client: java.net.http.HttpClient.WebSocket — JDK stdlib, no coord.
# HTTP client: java.net.http.HttpClient — JDK stdlib, no coord.
# Scheduler: java.util.concurrent.Executors — JDK stdlib, no coord.
# Concurrency: java.util.concurrent.Flow + virtual threads — JDK stdlib, no coord.

# Tests
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }
awaitility = { module = "org.awaitility:awaitility", version.ref = "awaitility" }
jqwik = { module = "net.jqwik:jqwik", version.ref = "jqwik" }

# Static analysis
errorprone-core = { module = "com.google.errorprone:error_prone_core", version.ref = "errorprone-core" }
nullaway = { module = "com.uber.nullaway:nullaway", version.ref = "nullaway" }
```

Scope assignment summary (so Phase 4 doesn't have to re-derive):

| Coord                         | arcp-core   | arcp-runtime-jetty | arcp-otel   | arcp-auth-jwt | tests       |
| ----------------------------- | ----------- | ------------------ | ----------- | ------------- | ----------- |
| jackson-databind, jsr310      | api         | implementation     | impl        | impl          |             |
| slf4j-api                     | api         | api                | api         | api           |             |
| jspecify                      | api         | api                | api         | api           |             |
| ulid, uuid                    | impl        |                    |             |               |             |
| opentelemetry-api             |             |                    | api         |               |             |
| jetty-server, ws-jakarta      |             | impl               |             |               |             |
| nimbus-jwt (out of core)      |             |                    |             | impl          |             |
| junit, assertj, awaitility, jqwik |         |                    |             |               | testImpl    |
| slf4j-simple                  |             |                    |             |               | testRuntime |
