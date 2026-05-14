# 07 — Test Strategy

Inputs: `../../spec/docs/draft-arcp-02.1.md`, `01-spec-delta.md`,
`02-current-audit.md`. The current 87.4% JaCoCo number in
`02-current-audit.md` is over the v2-RFC code being deleted; the
floor below applies to the v1.1 surface defined in Phase 4.
Coverage from before Phase 7 does not roll over.

## 1. Stack

| Tool                        | Decision | Why |
| --------------------------- | -------- | --- |
| JUnit 5 (`org.junit.jupiter`) | Keep    | Already wired in `lib/build.gradle.kts:34`. Standard for parameterized + `DynamicTest` (conformance harness leans on `@TestFactory`). |
| AssertJ                     | Keep     | `lib/build.gradle.kts:36`. Reject Hamcrest (`assertThat(x, is(equalTo(y)))` versus AssertJ's `assertThat(x).isEqualTo(y)` — fewer imports, chainable, better diff messages on records). Reject built-in `org.junit.jupiter.api.Assertions` for non-trivial cases — no chained assertions, weaker failure output on collections and records. |
| jqwik                       | Add      | The v1.1 wire has six invariants (round-trip, monotonicity, idempotency, lease subset, budget arithmetic, chunk reassembly) that example-based tests under-cover. jqwik integrates with the JUnit 5 platform and produces shrunk counter-examples — meaningful when a 50-element `event_seq` sequence reveals a gap; the shrinker tells you the 2-element minimum that reproduces it. Reject QuickTheories (less actively maintained, no JUnit 5 platform integration as of 2026-05). |
| PIT (`pitest-junit5-plugin`) | Add, nightly | Mutation runs are 5–10× a unit-test pass on this surface. Per-PR is too slow; nightly with a published HTML report and a coverage floor is the trade-off. **Floor: 70% mutation coverage on `dev.arcp.runtime.*` and `dev.arcp.core.wire`**. Lower floor on `dev.arcp.client.*` (60%) because client surface is mostly orchestration that mutates poorly. Fails the nightly CI job — does not block PRs. |
| JaCoCo 0.8.13               | Keep     | Already at `lib/build.gradle.kts:98`. **Floor: 87% lines AND 87% branches** on the v1.1 surface (per-module enforcement via `jacocoTestCoverageVerification`). |
| Awaitility                  | Keep     | `lib/build.gradle.kts:37`. Required for any wait-for-condition; `Thread.sleep` is banned in tests (see §4). |
| **Mockito**                 | **Reject for state logic.** | Mockito's `verify(mock).foo()` couples tests to call order rather than observable behavior — every refactor that preserves semantics breaks the test. The v1.1 surface is dominated by state machines (session FSM, job FSM, budget counters, lease expiration clock). Use **hand-written fakes**: `FakeTransport` (in-memory frame queue), `FakeClock` (Phase 4 `Clock` SPI; controlled `Instant.now()`), `FakeAgentRegistry`, `FakeResumeStore`, `FakeIdempotencyStore`. Permitted Mockito use: thin SPIs with no internal state (e.g., an `AuthValidator` boundary returning `Principal` for a given token). Document this in `arcp-runtime/src/test/.../README.md`. |

## 2. Layered test plan

| Layer | Scope | Tooling | Where | Concrete v1.1 feature it covers |
| ----- | ----- | ------- | ----- | -------------------------------- |
| Envelope unit       | round-trip, unknown-field ignored, malformed shape, type discriminator, `arcp:"1"` invariant | JUnit + AssertJ | `arcp-core/src/test/.../wire/` | §5.1 unknown-field rule that lets v1.0 clients receive v1.1-only types without breaking |
| Message unit        | each v1.1 message record's decode/encode: `SessionHello/Welcome`, `SessionPing/Pong/Ack/ListJobs/Jobs`, `JobSubscribe/Subscribed/Unsubscribe`, `LeaseConstraints`, `ProgressBody`, `ResultChunkBody` | JUnit + AssertJ | `arcp-core/src/test/.../messages/` | §6.4, §6.5, §6.6, §7.6, §8.2.1, §8.4, §9.5 wire shapes |
| Session FSM         | `hello`→`welcome`; resume token rotation; heartbeat tick; ack record; bye | JUnit + `FakeTransport`+`FakeClock` | `arcp-runtime/src/test/.../session/` | §6.2 intersection rule, §6.3 rotation, §6.4 ping cadence, §6.5 `last_processed_seq` |
| Job FSM             | submit→accepted→events→result; cancel; idempotency dedupe; lease-expiration watchdog; budget decrement | JUnit + fakes | `arcp-runtime/src/test/.../job/` | §7.1 acceptance with `lease_constraints`, §7.4 cancel, §9.5 watchdog, §9.6 counter |
| Integration (mem)   | end-to-end client↔runtime over `MemoryTransport`; covers every v1.1 example flow in spec §13 | JUnit | `arcp-sdk/src/test/.../integration/` | §13.1–§13.7 examples (heartbeat, ack, list_jobs+subscribe, lease-expires-at, budget, result-chunk, agent-version) |
| Integration (ws)    | loopback over `127.0.0.1:0` via JDK `HttpClient.WebSocket` against an ephemeral runtime; one happy-path per feature; ensures the wire layer is not parsing-dependent on `MemoryTransport`'s frame timing | JUnit + ephemeral server | `arcp-sdk/src/test/.../e2e/` | §4.1 WebSocket transport, §6.4 heartbeat across a real socket |
| Property            | envelope round-trip, `event_seq` monotonicity, idempotency dedupe, lease subset, budget arithmetic, chunk reassembly | jqwik | `arcp-core/src/test/.../property/` (envelope/messages) + `arcp-runtime/src/test/.../property/` (FSM properties) | §5.1, §8.3, §7.2, §9.4, §9.6, §8.4 |
| Conformance harness | one `DynamicTest` per row in `CONFORMANCE.md`; mirrors the TS table 1:1 so a reviewer can diff the two SDKs row-by-row | JUnit `@TestFactory` | `arcp-tck/src/test/.../` (new module) | every §§ row in `CONFORMANCE.md` |
| Mutation            | per-module PIT (`dev.arcp.core.wire`, `dev.arcp.core.messages`, `dev.arcp.runtime.*`, `dev.arcp.client.*`) | PIT (nightly) | CI job `mutation-nightly` | guards §9.5/§9.6 enforcement and §5.1 parser against silent regressions |

## 3. Property tests (jqwik) — required

Each property names the spec § it defends and the failure mode it catches.

1. **Envelope round-trip — §5.1.** `forAll(envelopes) -> parse(serialize(env)).equals(env)` modulo unknown-field stripping. Generator emits records covering every v1.1 message type; an `arbitraryUnknownFields` provider injects `x-vendor.*` fields the parser must drop. Catches: serializer asymmetry, accidental enum ordering reliance.
2. **`event_seq` monotonicity — §8.3.** Given any interleaving of `JobEvent`s emitted across N concurrent jobs in one session under a `StructuredTaskScope`, the emitted seq numbers are strictly monotonic and gap-free. Generator: 1–8 jobs × 0–500 events each, random submission order. Catches: lock-free `AtomicLong.incrementAndGet` followed by an unsafe publish that allows two emitters to observe the same `seq` if the write order races the increment.
3. **Idempotency dedupe — §7.2.** For arbitrary `(idempotency_key, agent, input)` triples with random submission order within a 24h window: identical triples return the same `job_id`; same key with different `input` or `agent` returns `DUPLICATE_KEY`; after 24h elapsed on `FakeClock`, the key is reusable. Catches: hash-collision treating different inputs as equal; TTL sweep evicting too eagerly.
4. **Lease subset — §9.4.** For arbitrary delegation chains 1–6 deep: at every level, `delegated.expires_at ≤ parent.expires_at` (treating absent parent expiry as `Instant.MAX`) and for every currency `c`, `delegated.cost.budget[c] ≤ parent.remaining[c]`. Generator produces parent leases and proposed child leases including adversarial cases (child expiry exactly equal, child expiry one millisecond later, child budget exactly equal, child budget one cent over). Catches: off-by-one on the `≤` boundary, `BigDecimal` `compareTo` mistakes versus `equals` (`USD:5.00`.equals(`USD:5`) is false even though they're equal numerically).
5. **Budget arithmetic — §9.6.** For random sequences of `metric` decrements `[0.0001, 100.00]` against a `BigDecimal` counter initialized at a random value: final remaining equals `initial - sum(decrements)` exactly; no decrement is silently lost; negative metric values are rejected and produce no decrement (separate property). Generator includes adversarial inputs: `0.1 + 0.2 != 0.3` under `double`, very small (`USD:0.00001`) values, and large values approaching `int.MAX_VALUE`. Catches: any `double` arithmetic that crept into the code, including via Jackson default float parsing if `USE_BIG_DECIMAL_FOR_FLOATS` is not set.
6. **Result-chunk reassembly — §8.4.** For arbitrary chunk sequences `[c0, c1, …, cN]` where `c0..c(N-1)` have `more=true` and `cN` has `more=false`, encodings randomly `utf8` or `base64`, total size up to 30 MB: the reassembled bytes equal `concat(decode(c.data, c.encoding))` in `chunk_seq` order. Out-of-order delivery, duplicates, and gaps each produce a typed failure (`OutOfOrderChunkException`, `DuplicateChunkException`, `MissingChunkException`). Catches: reassembler that assumes arrival order matches `chunk_seq`, base64 padding bugs at chunk boundaries.

All six properties run with 100 iterations by default (jqwik default), 1000 in the nightly CI job.

## 4. Cancellation tests

The cancellation surface is the highest-confidence path to a Java-idiom regression. Three tests:

1. **`StructuredTaskScope` propagation.** A `StructuredTaskScope.ShutdownOnFailure` forks N=8 agent virtual threads. The outer scope is shut down; assert every forked thread observes an `InterruptedException` and the scope returns from `join()` within 100ms. The 100ms wall-clock bound is **not flaky under `Awaitility.await().atMost(Duration.ofMillis(500)).pollInterval(Duration.ofMillis(10)).until(...)`** because Awaitility polls a predicate (here: `scope.isShutdown() && allThreadsTerminated()`) rather than sleeping a fixed interval — the test passes as soon as the condition holds, only fails after the budget. The 100ms claim is a *negative-flakiness budget*, not a precise measurement: actual wall-clock is typically <10ms on JDK 21+; 100ms is the budget below which we still call the implementation conforming.
2. **`job.cancel` propagation.** Submit a job whose handler blocks on `Thread.sleep` (only place in the codebase `Thread.sleep` is acceptable — modeling a blocking agent). Send `job.cancel`. Assert: the agent virtual thread receives `Thread.interrupt()`, the handler's `InterruptedException` propagates, the `StructuredTaskScope` unwinds, and a `job.error{final_status:"cancelled"}` event is emitted on the wire within the 30s grace per §7.4. Awaitility used to wait for the wire event; no `Thread.sleep` in the test code itself.
3. **Cancellation during `result_chunk` streaming.** Submit a job that has emitted 10 chunks with `more=true`. Cancel. Assert: no further `result_chunk` events appear; a `job.error{final_status:"cancelled"}` arrives; partially assembled bytes on the client are discarded (not surfaced as a partial success).

`Thread.sleep` is banned in test code via a custom ArchUnit rule (Phase 4 already adds ArchUnit) — exception: the agent-handler fake above, which is documented as modeling external blocking I/O.

## 5. v1.0/v1.1 compatibility matrix

Four cells (from `01-spec-delta.md` §"client/runtime compatibility matrix"). Each gets at least one integration test in `arcp-tck/`:

| Client | Runtime | Test name pattern | What it asserts |
| ------ | ------- | ----------------- | --------------- |
| v1.0   | v1.0    | `Compat_v10Client_v10Runtime_baseline` | Sanity. v1.0 message set works, no v1.1 features negotiated. |
| v1.0   | v1.1    | `Compat_v10Client_v11Runtime_listJobsFeatureNotNegotiated` | Client sends no `features` array; runtime's `welcome` includes rich `agents` and `features`, but the client ignores them per §5.1; calling `ArcpClient.listJobs()` throws `FeatureNotNegotiatedException` (client-side guard). |
| v1.1   | v1.0    | `Compat_v11Client_v10Runtime_flatAgentsTreatedAsNoVersionInfo` | Runtime returns flat `agents: ["name", ...]`; client interprets as "no version info"; submitting `name@1.0.0` fails locally with `AgentVersionNotAvailableException` before hitting the wire (since the runtime cannot negotiate `agent_versions`). |
| v1.1   | v1.1    | `Compat_v11Client_v11Runtime_fullIntersection` | All nine v1.1 features negotiated; the conformance harness rows for v1.1 pass. |

These run inside `arcp-tck`; the v1.0 stand-in is a deliberately stripped-down `MemoryTransport` peer that refuses to emit any feature in `V1_1_FEATURES`.

## 6. CI matrix

| Axis | Values | Why |
| ---- | ------ | --- |
| JDK  | 21 LTS, 25 LTS | 21 is the BOOTSTRAP floor (`02-current-audit.md` §"toolchain"); 25 is the current toolchain target. Both are LTS; running both catches the `--release 21` regression where the library is accidentally compiled with JDK 25 bytecode. JDK 17 is **out of scope** per BOOTSTRAP "minimum JDK 21" — adding it would force giving up virtual threads (`Thread.ofVirtual`, stable in 21) and `StructuredTaskScope` (preview in 21 via `--enable-preview`, stable in 25), both of which the runtime depends on. |
| OS   | Linux only (`ubuntu-latest`) | Maven Central artifacts are JVM-portable; nothing in `arcp-core`, `arcp-runtime`, or `arcp-client` touches a platform-specific API (no `fork()`, no `epoll`, no Win32). The only OS-sensitive surface would be a path-canonicalization test (§14 mentions canonicalization for lease enforcement); cover it with **explicit `Path` fixtures** rather than CI matrix variance. macOS/Windows runners are 4–6× the cost of Linux on GitHub Actions for zero coverage gain. |
| WebSocket reverse-proxy | **Yes — single nginx pass-through test.** | One JUnit test in `arcp-sdk/src/test/.../e2e/NginxPassThroughE2ETest.java` starts an `nginx:alpine` container via Testcontainers configured for WebSocket upgrade pass-through, points the client at the proxy, and runs the heartbeat + result_chunk happy paths. Justification: 90% of real deployments terminate `wss://` at a reverse proxy; the most common bug is the proxy dropping `Connection: Upgrade` or rewriting the path away from `/arcp`. One test catches the regression; running it on every PR is fine (Testcontainers warm-up is <5s). Skipped if Docker is unavailable (local-dev fallback). |

## 7. "Minimum to hit 87%"

JaCoCo exclusions in `lib/build.gradle.kts` (after Phase 4 module split, repeated per module):

- **Generated code:** None expected — no annotation processors generate `.java` in the build.
- **`Main` classes:** If Phase 4 keeps `:cli`, exclude `dev.arcp.cli.Main` from the floor (interactive entry point, not unit-testable; covered by smoke E2E in `arcp-sdk/test/e2e/`).
- **`module-info.java`:** JaCoCo excludes by default (no executable instructions).
- **"Trivial getters" — refuse to exclude.** Records have no getters in the Lombok sense; the accessor is a one-line method generated by the compiler with non-trivial null/range checks where the record's compact constructor enforces invariants (e.g., `ProgressBody.current >= 0`). Excluding by pattern would also hide compact-constructor validation logic. Keep them in.

Per-package floor (sum must clear 87% line + 87% branch overall):

| Package                          | Line | Branch | Justification |
| -------------------------------- | ---- | ------ | ------------- |
| `dev.arcp.core.wire`             | 100% | 100%   | Envelope is ~80 lines, load-bearing. Anything less than 100% leaves a parser branch uncovered. |
| `dev.arcp.core.messages`         | 95%  | 90%    | Records with compact constructors; the 5% slack is for `toString`/`equals` paths records auto-generate, plus dead branches in `parseJobEventBody` for kinds the SDK doesn't yet recognize. |
| `dev.arcp.runtime.session`       | 90%  | 85%    | FSM transitions are tested directly via fakes; the 10% slack is for defensive logging branches and `INTERNAL_ERROR` paths that can only fire if upstream invariants are violated. |
| `dev.arcp.runtime.job`           | 90%  | 85%    | Same shape as session. |
| `dev.arcp.runtime.lease`         | 100% | 100%   | §9.5 + §9.6 are security-critical (subscription scope, lease enforcement, budget bypass — see spec §14). Anything less is a latent CVE. |
| `dev.arcp.runtime.budget`        | 100% | 100%   | `BigDecimal` arithmetic + per-currency counter map. The mutation test (§1) is the second line of defense. |
| `dev.arcp.client`                | 85%  | 80%    | Orchestration glue: `connect`/`submit`/`subscribe`/`listJobs`. The 15% slack covers retry logic that exercises every Java network failure mode (DNS, TLS, half-open TCP) — covered by integration tests, not unit. |
| `dev.arcp.client.results`        | 95%  | 90%    | `ResultStream` + `ResultSink` SPI. The reassembly path is property-tested (§3.6). |

The package floors weighted by line count exceed 87% overall; `jacocoTestCoverageVerification` runs both an overall rule (`COMPLEXITY` `BRANCH` ≥ 0.87, `LINE` ≥ 0.87) and per-package rules. Failing either fails the build.

## 8. Specific tests for v1.1 additions

One row per v1.1 feature (mirroring `01-spec-delta.md` and `CONFORMANCE.md`):

| Spec § | Feature             | Test class(es) |
| ------ | ------------------- | -------------- |
| §6.2   | Capability intersection | `CapabilityIntersectionTest` (asserts `intersect(hello, welcome)` excludes a feature absent from either; `FeatureNotNegotiatedException` on out-of-intersection use) |
| §6.4   | Heartbeats          | `HeartbeatLivenessTest`, `HeartbeatLossTest` (asserts `HEARTBEAT_LOST` on two missed intervals via `FakeClock` advance; asserts jobs continue running per spec) |
| §6.5   | Event ack           | `SessionAckTest`, `BackpressureSignalTest` (asserts `status{phase:"back_pressure"}` emitted when configured `backPressureThreshold` exceeded) |
| §6.6   | Job listing         | `ListJobsTest`, `ListJobsPaginationTest`, `ListJobsPrincipalScopingTest` (asserts no cross-principal job-existence leak per spec §6.6) |
| §7.5   | Agent versioning    | `AgentVersionResolutionTest` (bare-name resolves to default, `name@version` exact match, `AGENT_VERSION_NOT_AVAILABLE` on miss), `AgentVersionFixedAtAcceptanceTest` (asserts a running job's version never migrates) |
| §7.6   | Subscription        | `SubscribeReplayTest`, `SubscribeLiveTest`, `SubscribeAuthorizationTest` (cross-principal rejected by default), `SubscriberCancelDeniedTest` (asserts `PERMISSION_DENIED` on subscriber's `job.cancel` per spec §7.6) |
| §8.2.1 | `progress`          | `ProgressEventTest` (asserts `current >= 0` rejected at decode; asserts `current > total` accepted but logged per spec "SHOULD") |
| §8.4   | `result_chunk`      | `ResultChunkReassemblyTest`, `LargeResultStreamTest` (≥30 MB to a `FileResultSink` — asserts no in-memory buffer growth beyond 1 MB via `Runtime.getRuntime().freeMemory()` deltas; asserts `transferTo(OutputStream)` semantics), `InlineAndChunkMixingRejectedTest` (per spec §8.4 "MUST NOT mix") |
| §9.5   | Lease expiration    | `LeaseExpiredEnforcementTest` (asserts `LEASE_EXPIRED` on op at `expires_at`; asserts `INVALID_REQUEST` on past `expires_at` at submit; asserts UTC `Z` suffix required), `LeaseExpiryWatchdogTest` (asserts `job.error{code:"LEASE_EXPIRED"}` emitted when watchdog fires while agent is idle) |
| §9.6   | Budget              | `BudgetExhaustionTest` (asserts `BUDGET_EXHAUSTED` surfaced as `tool_result` error, not `job.error` — agent decides), `BudgetSubsetDelegationTest` (delegated child > parent.remaining rejected), `BudgetNegativeMetricRejectedTest`, `BudgetMultiCurrencyTest` (USD and credits tracked independently) |
| §12    | New error codes     | `ErrorCodeMappingTest` (asserts every code maps to its sealed exception subclass; asserts `retryable=false` invariant for `LEASE_EXPIRED`, `BUDGET_EXHAUSTED`, `AGENT_VERSION_NOT_AVAILABLE` per spec §12) |

## 9. Anti-slop guardrails

- **Banned words in test names, javadoc, and assertion messages:** `leverage`, `robust`, `scalable`, `performant`, `powerful`, `modern`, `enterprise-grade`, `battle-tested`, `production-ready`. Enforced by a `gradle check` task running `grep -r -i -E '(leverage|robust|scalable|performant|powerful|modern|enterprise-grade|battle-tested|production-ready)' src/test/` — non-zero exit fails the build. Words like `idiomatic` and `correct` are also banned in test names (every test claims to be correct; the name should describe what it asserts).
- **Every test description cites a spec § OR a specific Java idiom.** Reject `"tests cancellation"`. Accept `"asserts InterruptedException propagates through StructuredTaskScope.join within 100ms"` or `"spec §7.4: job.cancel produces job.error{final_status:'cancelled'} within 30s grace"`. The test name is the assertion, not the topic.
- **Reject tables that survive a language swap unchanged.** A test plan that reads the same in Java, TypeScript, Go, and Python is describing the spec, not the implementation. Every section above has at least one Java-specific anchor: `StructuredTaskScope`, `Flow.Publisher`, `BigDecimal` versus `double`, `Thread.interrupt`, JaCoCo, Testcontainers, jqwik shrinker, virtual threads. If a future edit removes those anchors, the plan has decayed into a generic checklist.
- **No mock-heavy tests without justification.** A test file that imports `org.mockito.Mockito` more than three times in non-SPI code is a code-review block. The reviewer asks for a fake.
