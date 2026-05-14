# 08 — Documentation strategy (docs/, README, CONFORMANCE, Javadoc)

Source spec: `../spec/docs/draft-arcp-02.1.md`. Model: `../typescript-sdk/README.md`,
`../typescript-sdk/CONFORMANCE.md`, `../typescript-sdk/docs/`. Demolition
target: the current `README.md` (claims "ARCP v1.0 per RFC-0001-v2",
which is the rejected draft per `02-current-audit.md` §"Headline finding")
and `CONFORMANCE.md` (self-describes "v0.1-WIP", reports `Implemented`
on §6.1/§7 for a wire shape that is not v1.0). Both files must be
rewritten end-to-end; the section below specifies the replacement
shapes.

## 1. `docs/` tree

Plain Markdown files. The broader ARCP docs site ingests `docs/` from
every SDK repo and renders it under `/java/`. The tree below is the
v1.1 surface; every file path is committed.

```
docs/
  00-overview.md
  01-quickstart.md
  02-concepts.md
  03-features/
    heartbeats.md
    ack.md
    list-jobs.md
    subscribe.md
    progress.md
    result-chunk.md
    lease-expires-at.md
    cost-budget.md
    agent-versions.md
  04-examples/
    submit-and-stream.md
    delegate.md
    resume.md
    idempotent-retry.md
    lease-violation.md
    cancel.md
    stdio.md
    vendor-extensions.md
    custom-auth.md
    heartbeat.md
    ack-backpressure.md
    list-jobs.md
    subscribe.md
    agent-versions.md
    lease-expires-at.md
    cost-budget.md
    progress.md
    result-chunk.md
  05-reference/
    api-overview.md
    error-taxonomy.md
    wire-format.md
  06-conformance.md
```

Per-file contract:

- `00-overview.md` — one paragraph on what ARCP is, one paragraph on
  what the Java SDK is, link to spec, link to `01-quickstart.md`.
  Cites §3 (Protocol Overview). No code.
- `01-quickstart.md` — mirrors `typescript-sdk/docs/getting-started.md`
  shape: prerequisites (JDK 21+), Gradle/Maven install, in-process
  demo using a paired in-memory `Transport`, then the same code over
  WebSocket. Code blocks compile against the v1.1 artifacts. CI
  extracts them per §5 below.
- `02-concepts.md` — envelope (§5.1), sessions (§6), jobs (§7), events
  (§8), leases (§9), errors (§12). Replicates the four-table layout
  from `typescript-sdk/README.md` "Core concepts" but anchored on
  Java types (`Envelope`, `Session`, `JobHandle`, `Event`, `Lease`,
  `ArcpException`).
- `03-features/*.md` — one file per v1.1 addition. Each MUST contain,
  in order:
  1. One-sentence summary citing the spec § anchor
     (e.g., "§6.4 — explicit session liveness via `session.ping` /
     `session.pong`").
  2. Wire shape block (JSON example pulled from
     `../spec/docs/draft-arcp-02.1.md`).
  3. Java surface block — the public types/methods that implement
     this feature, with `{@link}`-style Javadoc cross-links
     (rendered as anchored HTML by the docs site).
  4. Link to the example demonstrating it (`../04-examples/<name>.md`).
  5. Link to the Javadoc page for the primary type
     (`https://javadoc.arcp.dev/1.1.0/dev/arcp/...`).
  6. Negotiation note: the feature flag name and the behavior when
     the feature is not in the negotiated intersection
     (per `01-spec-delta.md` §"Capability negotiation").
- `04-examples/*.md` — one per example planned in Phase 6.
  Each file is a narrative wrapper around the example's runnable
  source: spec § cited, the file's command (`./gradlew
  :examples:<name>:run`), what to look for in the output, and a link
  to the full source. Examples themselves live under `examples/<name>/`
  per Phase 6; these docs link out, do not inline the source.
- `05-reference/api-overview.md` — package map (`dev.arcp.core`,
  `dev.arcp.client`, `dev.arcp.runtime`, `dev.arcp.otel`,
  `dev.arcp.middleware.jakarta`, `dev.arcp.middleware.spring`) with
  one-paragraph per package and a `{@link}` to the Javadoc index.
- `05-reference/error-taxonomy.md` — the 15 v1.1 codes (12 v1.0 +
  3 v1.1) with: code name, retryability, Java exception class
  (`AgentVersionNotAvailableException`, `LeaseExpiredException`,
  `BudgetExhaustedException`, etc., per `01-spec-delta.md` §"Three new
  error codes"), spec § anchor, one-line "when it fires".
- `05-reference/wire-format.md` — the v1.1 envelope shape (§5.1), the
  message taxonomy split into `session.*`, `job.*`, and event kinds.
  Identical scope to TS `docs/transports.md` plus envelope, but
  rewritten — Java-side: `Envelope` record, `MessageType` discriminator,
  Jackson registration seam (`ArcpMapper.create()`,
  `USE_BIG_DECIMAL_FOR_FLOATS = true` per `02-current-audit.md`
  §"What is salvageable"). No mention of the v2-RFC envelope fields
  (`stream_id`, `subscription_id`, `correlation_id`, …) that the
  current `Envelope.java` carries — those are being removed.
- `06-conformance.md` — mirrors `CONFORMANCE.md` at repo root. The
  docs-site copy exists so the rendered site has the conformance
  table without leaving `/java/`. Source of truth is the repo-root
  `CONFORMANCE.md`; this file is a build-time mirror (Phase 7 or 10
  decides whether it's a symlink or a copy-on-build).

## 2. Frontmatter schema

Every `.md` under `docs/` and the repo-root `README.md`/`CONFORMANCE.md`
carries YAML frontmatter. The shared docs site rejects files without it.

```yaml
---
title: "Heartbeats"
sdk: java
spec_sections: ["6.4"]
order: 1
kind: feature
since: "1.1.0"
---
```

Keys (all required unless noted):

- `title` — string. Doc title rendered in the docs site nav and `<h1>`.
  Does not have to match the filename.
- `sdk` — always `java` in this SDK. The docs site uses this for the
  language switcher and for tag-filtering search results.
- `spec_sections` — array of `§` strings (e.g., `["6.4"]`,
  `["7.5", "12"]`). The docs site builds a back-link from each spec
  section to every SDK doc that cites it. Empty array allowed for
  pure-Java content (e.g., `05-reference/api-overview.md` MAY be
  `[]`), but `00-overview.md`, `02-concepts.md`, and every
  `03-features/*.md` MUST cite at least one §.
- `order` — integer. Sort key within parent. Files at the same depth
  with the same `order` sort alphabetically as a tiebreaker.
- `kind` — one of `overview`, `quickstart`, `concept`, `feature`,
  `example`, `reference`, `conformance`. The docs site picks a
  template per `kind` (e.g., `feature` renders a "spec § + wire
  shape + Java surface + example + Javadoc" five-card layout).
- `since` — first version that documents this. v1.1 additions get
  `"1.1.0"`. The pre-existing v1.0 surface (envelope, sessions, jobs,
  leases, delegation) gets `"1.1.0"` too in this SDK because the
  Java SDK is being rebuilt for v1.1; there is no prior published
  Java version to anchor an earlier `since` (per
  `02-current-audit.md` "Is there an existing v1.0 release any
  consumer depends on? No"). When v1.2 ships, new docs get
  `"1.2.0"`; existing docs keep their original `since` value.

Optional keys:

- `deprecated` — string, the version that deprecated this surface.
  Unused at v1.1 launch; reserved.
- `javadoc` — string FQN (e.g., `dev.arcp.client.ArcpClient`). The
  docs site links the doc title to that Javadoc page.

## 3. Javadoc policy

The library publishes a Javadoc JAR via `withJavadocJar()`, which is
already wired in `lib/build.gradle.kts:16` (per `02-current-audit.md`
"What is salvageable"). After the Phase 4 module split, every module
(`arcp-core`, `arcp-client`, `arcp-runtime`, `arcp-otel`,
`arcp-middleware-*`) publishes its own Javadoc JAR. The hosted set is
served at `https://javadoc.arcp.dev/1.1.0/` (publication step planned
in Phase 10).

Rules:

- **Every public type and public method has Javadoc.** Package-private
  and private members SHOULD have Javadoc when behavior is non-obvious;
  they MAY skip it.
- **`@since` on every v1.1 addition.** Any class, method, field, or
  enum constant added in this SDK release carries `@since 1.1.0`.
  Classes touched but not added (e.g., `dev.arcp.core.wire.Envelope`,
  rewritten from the v2-RFC shape) also carry `@since 1.1.0` —
  consumers of the prior `0.1.0-SNAPSHOT` shape were never published
  (per `02-current-audit.md`), so the rebuilt surface is "since 1.1.0"
  end-to-end.
- **`@apiNote` for non-obvious behavior.** Examples:
  - On `ArcpClient.subscribe(...)`: "Heartbeat loss does NOT
    terminate jobs — see §6.4. A `HEARTBEAT_LOST` close affects
    only the session; subscribed jobs continue and may be observed
    via a fresh session."
  - On `JobHandle.collectChunks()`: "Buffers up to 1 MiB in memory.
    For larger payloads, prefer `streamResult(Path)` or
    `streamResult(OutputStream)`."
- **`@implNote` for internal-only quirks.** Examples:
  - On `SessionContext`: "Heartbeat is scheduled on a dedicated
    single-thread `ScheduledExecutorService`, not
    `StructuredTaskScope` — scope does not include scheduling
    (per `02-current-audit.md` §"High-risk items" item 4)."
  - On `BudgetCounter`: "Read-modify-CAS against
    `AtomicReference<BigDecimal>`. Do not introduce a lock here."
- **No bare `@param` / `@return` placeholders.** Spotless +
  Checkstyle MUST fail the build when a Javadoc tag has empty
  description text. The Checkstyle rule is `JavadocMethod` with
  `validateThrows=true` and `allowMissingParamTags=false`. Phase 7
  wires this check; Phase 8 confirms it gates `./gradlew check`.
- **Cross-link from prose docs to Javadoc.** Within
  `docs/03-features/*.md`, refer to symbols with
  `{@link dev.arcp.client.ArcpClient#subscribe(JobId, SubscribeOptions)}`.
  The docs site resolves these against the published Javadoc URL.
  The Java compiler does not validate `{@link}` inside Markdown, so
  Phase 7 adds a small script that greps `{@link …}` from `docs/` and
  asserts the FQN resolves to a public Javadoc page (404 check
  against `https://javadoc.arcp.dev/1.1.0/`).
- **Existing `module-info.java` Javadoc rewrite.** The current file
  references "RFC 0001 v2" (per `02-current-audit.md`). Phase 4
  rewrites the `module-info.java` set; each one MUST cite the v1.1
  spec at `../spec/docs/draft-arcp-02.1.md` and list which §s the
  module implements.

## 4. README outline

The new `README.md` replaces the current one entirely. The current
file claims `RFC-0001-v2`, references `:cli`, `:examples`, and
`SQLite event log` (all v2-RFC artifacts per `02-current-audit.md`);
none of those survive Phase 4.

Structure:

```markdown
---
title: "ARCP Java SDK"
sdk: java
spec_sections: []
order: 0
kind: overview
since: "1.1.0"
---

# ARCP Java SDK

Java SDK for [ARCP v1.1](../spec/docs/draft-arcp-02.1.md). Targets
JDK 21 LTS; tested on 21 and 25. Depends on Jackson and SLF4J only
at the `api` level.

## Install

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.arcp:arcp:1.1.0")           // umbrella
    // — or, granular —
    implementation("dev.arcp:arcp-client:1.1.0")    // client only
    implementation("dev.arcp:arcp-runtime:1.1.0")   // runtime only
}
```

### Maven

```xml
<dependency>
  <groupId>dev.arcp</groupId>
  <artifactId>arcp</artifactId>
  <version>1.1.0</version>
</dependency>
```

## Quickstart

A complete client + runtime over the in-process transport. Same code
runs over WebSocket by swapping the transport — see
[`docs/01-quickstart.md`](./docs/01-quickstart.md).

[60-line Java code block. Demonstrates ArcpClient.connect(...) →
client.submit(...) → handle.subscribe() consuming Flow.Publisher<Event>
→ handle.result(). Uses `pairMemoryTransports()` (per Phase 3 transport
plan), `StaticBearerValidator`, and a single `ArcpRuntime
.registerAgent("echo", (input, ctx) -> ...)`. Must compile under
JDK 21. CI extracts and compiles it per §5 below.]

## Packaging

| Artifact                     | What's in it                                 | Depends on                |
| ---------------------------- | -------------------------------------------- | ------------------------- |
| `arcp-core`                  | wire types, errors, capability, ids          | none                      |
| `arcp-client`                | `ArcpClient`, `JobHandle`, `Flow.Publisher`  | `arcp-core`               |
| `arcp-runtime`               | `ArcpRuntime`, `ArcpServer`, `JobContext`    | `arcp-core`               |
| `arcp`                       | umbrella; re-exports client + runtime        | `arcp-client`, `arcp-runtime` |
| `arcp-otel`                  | OpenTelemetry span/attr adapter (§11)        | `arcp-runtime`            |
| `arcp-middleware-jakarta`    | Jakarta WebSocket (JSR 356) server adapter   | `arcp-runtime`            |
| `arcp-middleware-spring-boot`| Spring Boot 3.x starter + auto-config        | `arcp-runtime`            |

The exact module split is fixed in Phase 4. The umbrella `arcp`
artifact is intended for application code; library authors should
prefer the granular artifacts.

## JDK compatibility

| JDK     | Status                                                                    |
| ------- | ------------------------------------------------------------------------- |
| 21 LTS  | Supported floor. If JEP 480 preview is required, `--enable-preview`.      |
| 25 LTS  | Supported; preferred. `StructuredTaskScope` is stable in 25 (JEP 505).    |
| < 21    | Unsupported.                                                              |

The build toolchain is JDK 25; `--release 21` ensures the produced
bytecode is consumable on JDK 21 (per `02-current-audit.md` §"High-risk
items" item 7; Phase 3 lowers the current `release.set(25)` to 21).

## Conformance

Section-by-section status against ARCP v1.1 lives in
[`CONFORMANCE.md`](./CONFORMANCE.md). Spec: [`draft-arcp-02.1.md`](../spec/docs/draft-arcp-02.1.md).

## License

[Apache-2.0](./LICENSE).
```

What this README is NOT:

- Not a tutorial. Tutorials live under `docs/01-quickstart.md`.
- Not a feature catalog. Features live under `docs/03-features/`.
- Not a marketing surface. No "leverage the power of", no "easy to
  use", no "production-ready", no emoji.
- Not a changelog. `CHANGELOG.md` owns release notes.

## 5. Voice rules

- Terse. The README target is two minutes; the longest doc target
  (`02-concepts.md`) is eight minutes.
- No marketing vocabulary. No "leverage", "robust", "scalable",
  "performant", "powerful", "modern" (as a substitute adjective),
  "enterprise-grade", "battle-tested", "production-ready", "easy",
  "simple", "intuitive". Phase 7 adds a `./gradlew checkDocsVoice`
  task that greps for the banned list across `docs/`, `README.md`,
  and `CONFORMANCE.md` and fails the build on any hit.
- No emoji.
- Active voice. State requirements directly:
  - "Set `USE_BIG_DECIMAL_FOR_FLOATS = true` on the SDK's
    `ObjectMapper`" — not "the ObjectMapper should have BigDecimal
    enabled."
  - "The runtime sends `session.welcome.payload.heartbeat_interval_sec`"
    — not "the heartbeat interval is communicated via the welcome
    message."
- **Cite the spec § when describing protocol behavior.** The Java SDK
  does not invent protocol semantics; it implements §6.4, §6.5, etc.
  Any paragraph that describes wire behavior without a § citation is
  rejected at review.
- **Code blocks compile.** Phase 7's CI MUST extract every fenced
  ```java code block from `docs/01-quickstart.md` and from the
  `## Quickstart` section of `README.md`, drop them into a
  `src/test/java/dev/arcp/docs/QuickstartCompilesTest.java` shim,
  and compile them under JUnit. Extraction uses Markdown fence
  parsing (any fence tagged ` ```java `), not regex over raw text.
  The fenced blocks MUST be self-contained or carry a leading
  `// imports: ...` comment line listing the imports needed —
  Phase 7 defines the exact tagging convention.
- Reference Java idioms by their JDK name. `Flow.Publisher<Event>`,
  not "a reactive stream of events". `ScheduledExecutorService`,
  not "a background timer". `BigDecimal`, not "decimal type".
- Use the same artifact and class names everywhere. The umbrella is
  `dev.arcp:arcp`. The client is `dev.arcp.client.ArcpClient`. Not
  `ARCPClient`, not `ArcpJavaClient`, not "the client". Case-folded
  (`ArcpClient`) per Java naming convention; the existing
  `ARCPClient` / `ARCPRuntime` names in `02-current-audit.md` get
  renamed in Phase 4.

## 6. `CONFORMANCE.md` target shape

Mirror `../typescript-sdk/CONFORMANCE.md` exactly. The top of the file:

```markdown
---
title: "Conformance — ARCP v1.1 (Java SDK)"
sdk: java
spec_sections: []
order: 999
kind: conformance
since: "1.1.0"
---

# Conformance — ARCP v1.1 (Java SDK)

This SDK targets [ARCP v1.1](../spec/docs/draft-arcp-02.1.md), the
backward-compatible additive revision of v1.0.

- **Implemented** — feature is present and matches the spec.
- **Deferred** — feature is intentionally not yet implemented, with rationale.
```

Then one table per spec § from §4 through §15, in spec order:

```markdown
## §4. Transport

| Requirement                                                | Status      | Location                                                   |
| ---------------------------------------------------------- | ----------- | ---------------------------------------------------------- |
| §4.1 WebSocket MUST be supported (`wss://`, `/arcp` path)  | Implemented | `runtime/src/main/java/dev/arcp/runtime/ws/WebSocketServer.java:42` |
| §4.1 JSON text frames only                                 | Implemented | `core/src/main/java/dev/arcp/core/wire/JsonFrameCodec.java:18`     |
| §4.2 stdio newline-delimited JSON                          | Deferred    | Not in v1.1 first cut; planned for v1.2.                   |
| §4.3 Alternate transports MAY exist                        | Implemented | `core/src/main/java/dev/arcp/core/transport/MemoryTransport.java`  |
```

Row contract:

- Column 1 — requirement, prose lifted from the spec with a leading
  `§x.y` anchor. Phase 7 verifies that every `§x.y` substring in
  column 1 resolves to a heading in `../spec/docs/draft-arcp-02.1.md`.
- Column 2 — exactly `Implemented` or `Deferred`. No "Partial". The
  TS file uses these two values only; the Java version mirrors.
  If a feature is half-built, it is `Deferred` until done.
- Column 3 — `package/path:LineNumber` for a single load-bearing line,
  or `package/path` for a whole-file reference (e.g.,
  `core/src/main/java/dev/arcp/core/transport/MemoryTransport.java`).
  Class-only references (e.g., `dev.arcp.core.wire.Envelope`) are
  also permitted when the implementation is the whole class.

Bottom of the file, after all v1.0 §s:

```markdown
---

# v1.1 additions

## §6.2 Capabilities (v1.1 additions)
[table — features list, intersection rule, rich agents shape]

## §6.4 Heartbeats
[table — feature flag, ping/pong envelopes, interval advertisement, HEARTBEAT_LOST]

## §6.5 Event Acknowledgement
[table — feature flag, session.ack, recordAck, autoAck client helper]

## §6.6 Job Listing
[table — feature flag, list_jobs/jobs, JobListEntry, per-principal scope]

## §7.5 Agent Versioning
[table — feature flag, name@version grammar, AGENT_VERSION_NOT_AVAILABLE]

## §7.6 Subscription
[table — feature flag, subscribe/subscribed/unsubscribe, Flow.Publisher, no-cancel rule]

## §8.2 Progress events
[table — progress kind reserved, body schema, JobContext.progress]

## §8.4 Streamed results
[table — feature flag, result_chunk body, no inline+chunk mix, ResultStream]

## §9.4 Lease subsetting (v1.1 additions)
[table — child budget ≤ parent remaining, child expires_at ≤ parent]

## §9.5 Lease expiration
[table — feature flag, expires_at parsing, LEASE_EXPIRED]

## §9.6 Budget capability
[table — feature flag, currency:decimal grammar, BigDecimal arithmetic, BUDGET_EXHAUSTED]

## §11 Trace attributes (v1.1 additions)
[table — arcp.lease.expires_at, arcp.budget.remaining]

## §12 Error taxonomy (v1.1 additions)
[table — AGENT_VERSION_NOT_AVAILABLE, LEASE_EXPIRED, BUDGET_EXHAUSTED, all non-retryable]
```

Each v1.1 sub-table follows the same three-column contract.

Phase 7 (tests) writes the conformance harness against the rows: for
every row labeled `Implemented`, an integration or unit test exists
that exercises the cited file. The conformance harness fails the
build when a row claims `Implemented` but no test references the
cited location (grep-based check). Phase 8 owns this file as the
source of truth; `docs/06-conformance.md` is a build-time copy.

## 7. Non-goals for v1.1 docs

- **No tutorial site beyond `docs/`.** The shared ARCP docs site
  ingests `docs/` from this repo and renders it. There is no separate
  Java-only Jekyll/Docusaurus/MkDocs site.
- **No video, no slide deck, no blog post.**
- **No migration guide from the v0.1-WIP Java SDK to v1.1.** That SDK
  was never published — per `02-current-audit.md` §"Open questions
  for Phase 10": "Is there an existing v1.0 release any consumer
  depends on? No — `CHANGELOG.md` shows only pre-release notes;
  nothing is published. Safe to break." Phase 10 confirms before
  release. If a stray `0.1.0-SNAPSHOT` consumer surfaces during
  Phase 10, a migration guide gets added as `docs/07-migration.md`;
  until then, do not write it.
- **No CHANGELOG entries for pre-v1.1 work.** `CHANGELOG.md` resets
  at v1.1.0 with a single "Initial release targeting ARCP v1.1" line
  and nothing earlier.
- **No `:cli` documentation.** Per `02-current-audit.md` §"What is
  salvageable", the CLI is deferred to Phase 10's discretion. If the
  CLI ships in v1.1, `docs/cli.md` gets added; otherwise it does not
  appear in the tree above.

## 8. Anti-slop guardrails

Review checks (Phase 7 wires what can be automated; the rest is
PR-review discipline):

- **Banned vocabulary.** "leverage", "robust", "scalable",
  "performant", "powerful" (as substitute adjective), "modern" (as
  substitute adjective), "enterprise-grade", "battle-tested",
  "production-ready", "industry-leading", "world-class",
  "cutting-edge", "next-generation". `./gradlew checkDocsVoice`
  greps the literal strings, case-insensitive, across `docs/`,
  `README.md`, `CONFORMANCE.md`, and `CHANGELOG.md`. Hits fail the
  build with a one-line `path:line: banned word "..."` error.
- **Citation requirement.** Every paragraph that describes protocol
  behavior MUST cite a spec § anchor. PR review rejects paragraphs
  that don't cite either (a) a spec §, (b) a TypeScript SDK path
  for behavior parity, (c) a named Java idiom (`Flow.Publisher`,
  `ScheduledExecutorService`, `BigDecimal`), or (d) a named
  artifact (`arcp-client`, `ArcpClient`). This is enforced at
  review, not by tooling.
- **Language-swap test.** A table or paragraph that survives a
  language swap unchanged is generic and rejected. The packaging
  table in `README.md` survives the test (specifies `arcp-core`,
  `arcp-client`, `dev.arcp:arcp` — Java-specific). A generic
  "features" table listing "Heartbeats — keep sessions alive" would
  not (it could appear unchanged in the TS SDK docs).
- **No "see the spec for details" punts.** If the doc cites a § and
  doesn't explain what that § means for a Java caller, the doc is
  rejected. Citing without explanation is worse than not citing.
- **Numeric specifics.** Where the spec gives a number (e.g., 30s
  cancel grace per §7.4; 2 missed intervals per §6.4), the docs
  state the number. "A short grace period" is not acceptable.
- **No future-tense feature promises.** Docs describe what the v1.1
  release does. Forward-looking statements live in `CHANGELOG.md`
  under an explicit `## Unreleased` heading or in a separate
  `ROADMAP.md` (not in scope for v1.1).

## 9. Ownership and handoff

- **This phase (Phase 8) owns:** `docs/` tree, `README.md`,
  `CONFORMANCE.md`, frontmatter schema, Javadoc policy, voice rules.
- **Phase 6 (examples) owns:** the `examples/<name>/` source trees
  that `docs/04-examples/*.md` link to.
- **Phase 7 (tests) owns:** the `checkDocsVoice` task, the Markdown
  code-block compilation harness, the `{@link}` resolver, and the
  conformance-row → test linkage check.
- **Phase 10 (release) owns:** publishing the Javadoc JARs to
  `https://javadoc.arcp.dev/1.1.0/`, the `docs/` ingestion hook
  into the shared docs site, and the `CHANGELOG.md` reset.

## 10. Length budget

| Doc                          | Target read time | Word ceiling (approx) |
| ---------------------------- | ---------------- | --------------------- |
| `README.md`                  | 2 min            | 500                   |
| `docs/00-overview.md`        | 2 min            | 500                   |
| `docs/01-quickstart.md`      | 5 min            | 1200                  |
| `docs/02-concepts.md`        | 8 min            | 2000                  |
| `docs/03-features/*.md`      | 3 min each       | 700 each              |
| `docs/04-examples/*.md`      | 2 min each       | 400 each              |
| `docs/05-reference/*.md`     | 5 min each       | 1200 each             |
| `docs/06-conformance.md`     | (table; no prose target) | n/a           |
| `CONFORMANCE.md` (root)      | (table; no prose target) | n/a           |

Total v1.1 documentation surface ≤ 8 minutes of end-to-end reading
for the `README.md` → `00-overview.md` → `01-quickstart.md` path. A
consumer who reads only those three files can install the SDK and
run a job.
