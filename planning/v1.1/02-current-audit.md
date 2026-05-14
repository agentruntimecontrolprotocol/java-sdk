# 02 — Current Java SDK Audit

## Headline finding

**The Java SDK is not implementing v1.0. It is implementing a
prior, rejected draft.** Every other planning document below is
shaped by this fact.

Evidence:

- `lib/src/main/java/dev/arcp/envelope/Envelope.java:59` —
  `PROTOCOL_VERSION = "1.0"`, but the wire shape (`stream_id`,
  `subscription_id`, `correlation_id`, `causation_id`,
  `idempotency_key`, `priority`, `source`, `target`, `extensions`
  as envelope-level fields) matches `../spec/docs/draft-arcp-01.md`
  §6.1 — NOT `../spec/docs/draft-arcp-02.md` §5.1 (v1.0 actual,
  which requires `arcp: "1"` and an envelope with only
  `{arcp, id, type, session_id?, trace_id?, job_id?, event_seq?,
  payload}`).
- `lib/src/main/java/module-info.java:3` references
  "RFC 0001 v2"; `PLAN.md`, `README.md`, `CHANGELOG.md`,
  `RFC-0001-v2.md` are all written against the v2 RFC.
- `lib/src/main/java/dev/arcp/capability/Capabilities.java:15` —
  fields (`anonymous`, `streaming`, `human_input`, `permissions`,
  `artifacts`, `subscriptions`, `interrupt`,
  `heartbeat_recovery`, `heartbeat_interval_seconds`,
  `binary_encoding`, `extensions`) are the v2-RFC capability
  surface. v1.0 (§6.2) uses `capabilities.encodings` +
  `capabilities.agents` only; v1.1 (§6.2) adds
  `capabilities.features[]`.
- `lib/src/main/java/dev/arcp/error/ErrorCode.java:11` — 21 codes
  modeled after gRPC. v1.0 (`draft-arcp-02.md` §12) has 12 codes;
  v1.1 adds three for 15 total. Overlap is partial; names do not
  match.
- `CONFORMANCE.md:1` self-describes as "v0.1-WIP". The current
  code is not v1.0-complete, let alone v1.1-ready.
- `lib/build.gradle.kts:117` — POM description still claims
  "Java reference implementation … (ARCP) v1.0." The artifact ID
  is `arcp:0.1.0-SNAPSHOT`.

**Implication for migration:** v1.1 work cannot land additively
on top of this codebase. The wire layer, envelope record, message
taxonomy, capability record, and error code enum must be
replaced. Subsequent phases (3–9) plan the v1.1 destination; this
phase documents what must be torn down to get there.

## Module layout (as-built)

`settings.gradle.kts` declares three subprojects:

| Subproject  | Purpose                                                    | Build file                              | Notes                                                                                  |
| ----------- | ---------------------------------------------------------- | --------------------------------------- | -------------------------------------------------------------------------------------- |
| `:lib`      | Core SDK: envelope, transport, runtime, client, messages.  | `lib/build.gradle.kts` (139 lines)      | JPMS `module arcp` (single module — does not separate client from runtime).            |
| `:cli`      | `ArcpCli` entry point.                                     | `cli/build.gradle.kts`                  | Single file, `cli/src/main/java/dev/arcp/cli/ArcpCli.java`.                            |
| `:examples` | 14 example mains under `dev.arcp.examples.*`.              | `examples/build.gradle.kts`             | Aggregated; no per-example subproject (Phase 6 will split them).                       |

Toolchain: `JavaLanguageVersion.of(25)` in `lib/build.gradle.kts:13`.
BOOTSTRAP.md mandates JDK 21 LTS floor. JDK 25 is fine for the
toolchain but `options.release.set(25)` will produce JDK 25
bytecode unconsumable by JDK 21 callers. **Phase 3 / 4 must drop
`--release` to 21**, keep the toolchain free to bump.

### `:lib` packages and public exports (per `module-info.java`)

```
dev.arcp                         — Version
dev.arcp.envelope                — Envelope (record), MessageType, Priority, EnvelopeDeserializer, ARCPMapper
dev.arcp.ids                     — 10 strongly-typed IDs: MessageId, SessionId, JobId, StreamId, TraceId, SpanId, LeaseId, ArtifactId, SubscriptionId, IdempotencyKey
dev.arcp.error                   — ARCPException, ErrorCode (21 codes), Lease{Expired,Revoked}Exception, PermissionDeniedException, UnimplementedException
dev.arcp.extensions              — ExtensionNamespace, ExtensionRegistry
dev.arcp.capability              — Capabilities, CapabilityNegotiator
dev.arcp.auth                    — Credentials, Identity, Principal, CredentialValidator, StaticBearerValidator, JwtValidator
dev.arcp.transport               — Transport, MemoryTransport
dev.arcp.runtime                 — ARCPRuntime, SessionState
dev.arcp.client                  — ARCPClient (71 lines; only handshake)
dev.arcp.messages.control        — Ping, Pong, ControlMessages
dev.arcp.messages.session        — SessionMessages (SessionOpen, SessionAccepted, etc.)
```

Subpackages present but **not** exported (planned but unwritten):
`dev.arcp.messages.execution`, `messages.streaming`, `messages.human`,
`messages.permissions`, `messages.subscriptions`, `messages.artifacts`,
`messages.telemetry`. The directories exist (`find` shows them) but
contain no `.java` files yet beyond `package-info.java`.

### Dependency surface (`lib/build.gradle.kts`)

| Coord (via `libs.versions.toml`)                   | Scope            | Used in v1.1?                                                                                                   |
| -------------------------------------------------- | ---------------- | --------------------------------------------------------------------------------------------------------------- |
| `com.fasterxml.jackson.core:jackson-databind`      | api              | Yes — JSON binding.                                                                                             |
| `com.fasterxml.jackson.datatype:jackson-jsr310`    | api              | Yes — `Instant` for `expires_at` (§9.5).                                                                        |
| `org.slf4j:slf4j-api` (2.x)                        | api              | Yes — library facade.                                                                                           |
| `org.jspecify:jspecify`                            | api              | Yes — nullness annotations.                                                                                     |
| `org.xerial:sqlite-jdbc`                           | implementation   | **Re-evaluate.** Used for `EventLog` durable replay. v1.1 §6.3 resume buffer is in-memory by default in TS.     |
| `org.java-websocket:Java-WebSocket`                | implementation   | **Replace.** Phase 3 should choose between JDK `HttpClient.WebSocket` (client) and Jetty/Undertow (server).     |
| `com.nimbusds:nimbus-jose-jwt`                     | implementation   | **Drop unless needed.** v1.0 (§6.1) defines only bearer; v1.1 does not add new auth. Keep if `signed_jwt` stays. |
| `com.github.f4b6a3:ulid-creator`                   | implementation   | Yes.                                                                                                            |
| `com.networknt:json-schema-validator`              | implementation   | **Drop.** v1.1 SDK uses Jackson + records for validation, not JSON Schema.                                     |
| Error Prone, NullAway, Spotless (`eclipse()`)      | build            | Keep.                                                                                                           |
| JaCoCo 0.8.13                                      | build            | Keep — 87% floor in Phase 7.                                                                                    |
| JUnit 5, AssertJ, Awaitility, `slf4j-simple` (test)| test             | Keep; Phase 7 adds jqwik + PIT.                                                                                |

Spotless uses `eclipse()` rather than Palantir per
`lib/build.gradle.kts:84-89` (Palantir 2.68.0 incompatible with
JDK 25 javac internals). Keep Eclipse until Palantir publishes a
JDK 25 build, then revisit.

## v1.1 gap matrix

| Spec §  | v1.1 feature             | Java current state                                                                                          | Java target package          | Risk | Notes                                                                                                                                                      |
| ------- | ------------------------ | ----------------------------------------------------------------------------------------------------------- | ---------------------------- | ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| §5.1    | Envelope (`arcp: "1"`)   | **Wrong wire.** `Envelope.java:59` says `"1.0"`; has 12+ extraneous fields per v2-RFC.                      | `dev.arcp.core.wire`         | H    | Replace `Envelope` record top-to-bottom; field set is a strict subset.                                                                                     |
| §6.1    | Bearer auth              | **Partial.** `StaticBearerValidator` exists; auth flow uses `session.open`/`session.accepted` (v2-RFC), not `session.hello`/`session.welcome` (v1.0).      | `dev.arcp.core.auth`         | M    | Keep validator; rewrite handshake.                                                                                                                         |
| §6.2    | Capability negotiation   | **Wrong shape.** `Capabilities` record carries booleans (`streaming`, `humanInput`, …); v1.1 needs `features: List<String>` + intersection.                | `dev.arcp.core.capabilities` | H    | Plan in 01-spec-delta §"Capability negotiation". Introduce internal `enum Feature`; treat the wire as strings only at the seam.                          |
| §6.3    | Resume                   | **Partial.** `EventLog.replay` exists. Needs `resume_token` rotation per welcome and `last_event_seq` semantics.                                            | `dev.arcp.runtime.resume`    | M    | Java friction: rotation must be atomic against concurrent send; `AtomicReference<ResumeToken>` per session.                                                |
| §6.4    | Heartbeats               | **Misaligned.** `ControlMessages` has generic `ping`/`pong` (v2-RFC §6.2 transport pings). v1.1 needs `session.ping`/`session.pong` with nonce + timestamps.| `dev.arcp.core.heartbeat`    | M    | Schedule pings on a `ScheduledExecutorService` per session; `StructuredTaskScope` does not include scheduling — separate executor is unavoidable.          |
| §6.5    | Event ack                | Missing.                                                                                                    | `dev.arcp.core.ack`          | L    | `session.ack { last_processed_seq }` on hot path; `AtomicLong`, rate-limit emit.                                                                          |
| §6.6    | Job listing              | Missing.                                                                                                    | `dev.arcp.client` + runtime  | L    | `session.list_jobs`/`session.jobs` with filter + cursor.                                                                                                  |
| §7.1    | `job.submit`             | Missing (`messages.execution` directory has no `.java`).                                                    | `dev.arcp.core.messages`     | H    | Whole jobs surface absent.                                                                                                                                |
| §7.5    | Agent versioning         | Missing.                                                                                                    | `dev.arcp.core.agents`       | M    | `name@version` grammar parsed at the seam, never reconstructed downstream.                                                                                |
| §7.6    | Job subscription         | Missing. `examples/subscriptions/Main.java` simulates a v2-RFC `subscriptions` capability that no longer exists.                                            | `dev.arcp.client`            | H    | Must return `Flow.Publisher<Event>`. WebSocket back-pressure: use `SubmissionPublisher` boundary, drop-on-overflow with warning (v1.1 has no wire credit). |
| §8.2    | `progress` event         | Missing.                                                                                                    | `dev.arcp.core.events`       | L    | Body record with `current` non-negative invariant.                                                                                                        |
| §8.4    | `result_chunk` streaming | Missing.                                                                                                    | `dev.arcp.client.results`    | H    | Decoding to `byte[]` is wrong for 30MB; surface `ResultStream` with `transferTo(OutputStream)` and `Flow.Publisher<ByteBuffer>`.                          |
| §9.5    | Lease `expires_at`       | **Misnamed.** `LeaseExpiredException` exists but is v2-RFC §15-driven. Wire shape (`lease_constraints.expires_at`) and grammar are missing.                | `dev.arcp.core.lease`        | M    | `Instant` only; parser rejects non-UTC offsets and past values at the seam.                                                                                |
| §9.6    | `cost.budget`            | Missing.                                                                                                    | `dev.arcp.core.lease.budget` | M    | `BigDecimal` arithmetic; set Jackson `USE_BIG_DECIMAL_FOR_FLOATS` at the seam; per-currency counters under `ConcurrentHashMap<String, AtomicReference<BigDecimal>>` (read-modify-CAS, not lock).|
| §12     | 3 new error codes        | `ErrorCode` enum has `LEASE_EXPIRED`; missing `BUDGET_EXHAUSTED`, `AGENT_VERSION_NOT_AVAILABLE`. Also has 18 codes from v2-RFC that v1.1 does not define.   | `dev.arcp.core.error`        | M    | Rebuild enum to the v1.1 set of 15; sealed `ArcpException` hierarchy (Phase 4).                                                                            |
| §11     | OTel trace attrs         | Trace fields on envelope (wrong place for v1.1) but no span emission.                                       | `arcp-otel` (Phase 5)        | L    | `arcp.lease.expires_at`, `arcp.budget.remaining`.                                                                                                          |

## v1.0 conformance summary vs TS

TS `CONFORMANCE.md` reports v1.0 fully implemented across §4–§13.
Java `CONFORMANCE.md` reports:

- §6.1 envelope **Implemented** — but the envelope is the v2-RFC
  shape, not v1.0. The "implemented" label is wrong by today's
  spec.
- §7 Capability negotiation **Implemented** — same problem;
  capability shape does not match v1.0 §6.2.
- §10 Jobs, §11 Streams, §12 Human-in-loop, §13 Subscriptions,
  §15.x Lease manager **Deferred** — never started.
- §22 Transports **Partial** — only `MemoryTransport`. WebSocket
  and stdio are not in `:lib`.

**Net v1.0 conformance: ~0%.** What works (auth scheme decode,
ULID generation, idempotency table, Jackson plumbing) is reusable
plumbing; the protocol surface is not.

## High-risk items (Java-specific friction)

1. **§7.6 subscribe over a `Flow.Publisher`.** A subscriber MAY
   issue `request(8)` to a `Flow.Subscription`; the SDK must
   translate that to credit-tracking against the WebSocket. v1.1
   does not specify a wire credit (Phase 4 must decide:
   drop-on-overflow with `WARN` log vs. closing the session on
   sustained backlog). TS chose drop-on-overflow
   (`packages/client/src/client.ts`); recommend matching.
   `SubmissionPublisher` from JDK is the right boundary —
   `submit(Event)` blocks producers when subscribers fall behind,
   which is exactly what we want at the WebSocket reader thread,
   but only if the reader is a virtual thread (real-thread
   blocking would stall the dispatcher).

2. **§8.4 result_chunk reassembly without buffering 30MB.**
   Jackson's default streaming reader is fine for the chunk
   envelope; the body's base64 `data` must be decoded into a
   sink. Plan a `ResultSink` SPI: `BytesResultSink` (small),
   `FileResultSink` (path + `FileChannel.transferFrom`),
   `StreamingResultSink` (`Flow.Publisher<ByteBuffer>`). Default
   sink is `BytesResultSink` but with a configurable max
   (defaults to 1MB and rejects beyond — fail closed).

3. **§9.6 budget arithmetic on `double` is wrong.** Jackson by
   default decodes numbers as `double`. The lease budget value
   (`USD:5.00`) is parsed once at submit; the `metric` event's
   `value` field is parsed per event. Set
   `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS = true` on
   the SDK's `ObjectMapper` at `ARCPMapper.create()` (existing
   class — `dev.arcp.envelope.ARCPMapper`). This is one line, but
   it's load-bearing — Phase 7 must property-test budget
   arithmetic with adversarial fraction inputs (e.g.
   `0.1 + 0.2 != 0.3` under `double`).

4. **§6.4 heartbeat scheduling.** No structured-concurrency
   primitive schedules — `StructuredTaskScope` is fork/join. A
   dedicated `Executors.newScheduledThreadPool(1)` per runtime
   instance, plus a per-session `ScheduledFuture` for the next
   ping, is the minimum viable design. Cancel on session close.
   `ScopedValue` is not relevant here.

5. **`Envelope` record rewrite ripples through 41 tests.** The
   existing `EnvelopeRoundTripTest`, `EnvelopeNegativeTest`,
   `HandshakeTest`, and all `messages/*` tests assume the v2-RFC
   envelope. They will not be salvageable. Phase 7 plans the
   replacement test pyramid; Phase 10 sequences the demolition.

6. **JPMS module split.** Phase 4 splits `arcp` into `arcp.core`,
   `arcp.client`, `arcp.runtime`. The current `module-info.java`
   opens packages to `com.fasterxml.jackson.databind` for
   reflection — when splitting, every new module that holds
   records must re-do those `opens`. This is mechanical but
   easy to miss.

7. **Toolchain vs `--release`.** `lib/build.gradle.kts:42` —
   `options.release.set(25)` makes the library only consumable
   by JDK 25 callers. The BOOTSTRAP mandates JDK 21 LTS floor.
   Phase 3 decides which JDK 21+ features get used (virtual
   threads — yes; `StructuredTaskScope` — yes since JEP 480 is
   preview in 21 and stable in 25). `--release 21` with
   toolchain at 25 keeps both happy.

## What is salvageable

| Asset                                                                | Verdict   | Notes                                                                                                  |
| -------------------------------------------------------------------- | --------- | ------------------------------------------------------------------------------------------------------ |
| `dev.arcp.ids.MessageId`, `JobId`, `SessionId`, `TraceId`, `SpanId`  | Keep      | Strong typing of IDs is right. Drop `StreamId`, `SubscriptionId`, `LeaseId`, `ArtifactId` (v2-RFC).    |
| `dev.arcp.util.Ulid`                                                 | Keep      | Wrapper over `ulid-creator`; correct.                                                                  |
| `dev.arcp.envelope.ARCPMapper`                                       | Keep, configure | Add `USE_BIG_DECIMAL_FOR_FLOATS`, `FAIL_ON_UNKNOWN_PROPERTIES=false`, `WRITE_DATES_AS_TIMESTAMPS=false`. |
| `dev.arcp.auth.StaticBearerValidator`                                | Keep      | Useful for v1.0 §6.1.                                                                                  |
| `dev.arcp.auth.JwtValidator`                                         | Keep optional | v1.1 spec does not require it. Move to an `arcp-auth-jwt` opt-in module per Phase 3.                |
| `dev.arcp.transport.MemoryTransport`                                 | Keep, rewrite for new wire | Test transport is essential.                                                              |
| `dev.arcp.store.EventLog` (SQLite)                                   | Demote    | v1.1 §6.3 resume buffer is in-memory in TS; SQLite is over-engineered for the v1.1 contract. Move to an `arcp-resume-sqlite` opt-in or delete.   |
| Spotless + Error Prone + NullAway config                             | Keep      | All clean per current `CONFORMANCE.md`.                                                                |
| JaCoCo 87.4% coverage on what exists                                 | Reset     | Coverage is over the wrong code. Phase 7 resets the floor against the new surface.                     |
| Existing examples (14)                                               | Discard   | Mostly map to v2-RFC concepts (`subscriptions`, `human_input`, `permission_challenge`, `lease_revocation`) that don't exist in v1.1. Phase 6 plans 18 fresh examples mirroring TS. |
| `:cli`                                                               | Defer     | Phase 6 / 10 decide whether a CLI is in scope for v1.1 first cut. TS has none.                         |
| `module-info.java`                                                   | Rewrite   | Phase 4 splits modules.                                                                                |

## Demolition order (informational; Phase 10 sequences for real)

1. Pin the spec target in `README.md` + `CONFORMANCE.md` to
   `draft-arcp-02.1.md`. Delete `RFC-0001-v2.md`.
2. Rewrite `Envelope` record to v1.1 fields only. All call
   sites in the 11 existing message records break — that is
   expected; new message records replace them.
3. Replace `Capabilities` with v1.1 shape
   (`encodings`, `features`, `agents`). Replace
   `CapabilityNegotiator` with the intersection rule.
4. Replace `ErrorCode` enum with the v1.1 fifteen-code set.
   Replace `ARCPException` with the sealed hierarchy from
   Phase 4.
5. Stand up v1.0 message records (sessions §6, jobs §7,
   events §8) before v1.1 additions on top.
6. Delete v2-RFC examples; add v1.1 examples per Phase 6.

## Open questions for Phase 10

- Keep the `dev.arcp` group / `arcp` artifact-ID — yes
  (per `build.gradle.kts:113`), but bump to `1.1.0-SNAPSHOT`
  and reset the version line on first PR.
- Is there an existing v1.0 release any consumer depends on? No
  — `CHANGELOG.md` shows only pre-release notes; nothing is
  published. Safe to break.
- CLI: defer the decision to Phase 10. If reviewers want it for
  the v1.1 cut, the existing `cli/` skeleton is reusable.
