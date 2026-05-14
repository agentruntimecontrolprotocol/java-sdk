# 01 — Spec Delta v1.0 → v1.1

Source: `../spec/docs/draft-arcp-02.1.md`. Every row cites the spec
section that defines it. "Additive" means a v1.0 Java client/runtime
keeps working without code changes; "Behavior change" means a v1.0
implementation can stay compliant but a v1.1 implementation must
gate the new behavior behind capability negotiation. Nothing in
v1.1 is wire-breaking.

## v1.1 additions

| Spec §  | Feature                       | Feature flag        | Conformance | Wire shape                                                              | Java client impact                                                                                     | Java runtime impact                                                                                                       | Additive vs breaking         |
| ------- | ----------------------------- | ------------------- | ----------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| §6.2    | Capability negotiation        | (gate for all)      | MUST        | `session.hello.payload.capabilities.features[]` ∩ `session.welcome`     | Send features array; compute intersection from `welcome`; refuse to use a feature outside the set      | Echo supported features; advertise enriched `agents` shape; reject use of un-negotiated features                          | Additive                     |
| §6.2    | Rich `agents` capability      | `agent_versions`    | SHOULD      | `agents: [{ name, versions[], default }]`                               | Detect flat vs rich; if rich, expose `AgentDescriptor` with version list                               | Emit rich shape unconditionally; flat shape is legal for back-compat only                                                 | Additive                     |
| §6.4    | Heartbeats                    | `heartbeat`         | SHOULD      | `session.ping` / `session.pong` with nonce, sent_at/received_at         | Schedule ping when idle; reply to ping; trip `HEARTBEAT_LOST` after 2 missed intervals                 | Send heartbeat_interval_sec in welcome; reply to ping; do NOT terminate jobs on heartbeat loss                            | Additive                     |
| §6.5    | Event acknowledgement         | `ack`               | MAY         | `session.ack { last_processed_seq }`                                    | Emit ack at most per-event or per few-hundred-ms                                                       | MAY free buffered events ≤ last_processed_seq early; MUST NOT free unacked events unless buffer-limited; advisory only    | Additive                     |
| §6.6    | Job listing                   | `list_jobs`         | MAY         | `session.list_jobs` / `session.jobs` with filter + cursor               | Provide `ArcpClient.listJobs(filter)` returning paginated `JobSummary`                                 | Enforce per-principal scope; MUST NOT leak job existence across principals                                                | Additive                     |
| §7.5    | Agent versioning              | `agent_versions`    | MAY         | `agent = name` or `name@version`; `AGENT_VERSION_NOT_AVAILABLE` on miss | Allow pinning; surface resolved version in `JobAccepted`                                               | Resolve bare name via advertised `default`; fix version at acceptance; never migrate running job                          | Additive                     |
| §7.5    | Three new error codes         | (taxonomy)          | MUST        | `AGENT_VERSION_NOT_AVAILABLE`, `LEASE_EXPIRED`, `BUDGET_EXHAUSTED`      | Decode + map to `AgentVersionNotAvailableException`, `LeaseExpiredException`, `BudgetExhaustedException` | Emit with `retryable: false` for `LEASE_EXPIRED` and `BUDGET_EXHAUSTED`                                                   | Additive (decode only on v1.0) |
| §7.6    | Job subscription              | `subscribe`         | MAY         | `job.subscribe` / `job.subscribed` / `job.unsubscribe`                  | `ArcpClient.subscribe(jobId, fromSeq?, history?)` returns `Flow.Publisher<Event>`                      | Authorize per principal; replay buffered events when requested; subscription does NOT carry cancel authority              | Additive                     |
| §8.2    | `progress` event kind         | `progress`          | MAY         | `kind: progress`, body `{ current, total?, units?, message? }`          | Decode into `ProgressEvent` record                                                                     | No protocol action; pass through                                                                                          | Additive                     |
| §8.2.1  | `progress` body rules         | `progress`          | MUST        | `current ≥ 0`, `current ≤ total` when total present                     | Validate on decode at SDK seam                                                                         | Validate on emit                                                                                                          | Additive                     |
| §8.4    | Result streaming              | `result_chunk`      | MAY         | `kind: result_chunk` + terminating `job.result` with `result_id`        | Reassemble in `chunk_seq` order; surface as `Flow.Publisher<ResultChunk>` and final assembled bytes    | Generate `result_id`; emit chunks in order; final `job.result` MUST reference `result_id`; no inline+chunk mixing         | Additive                     |
| §9.5    | Lease expiration              | `lease_expires_at`  | MUST gate   | `lease_constraints.expires_at` ISO 8601 UTC `Z`, must be future         | Validate UTC + future at SDK seam; map enforcement error to `LeaseExpiredException`                    | Evaluate on every lease op; emit `LEASE_EXPIRED`; MAY proactively terminate                                              | Additive                     |
| §9.6    | Budget capability             | `cost.budget`       | MUST gate   | `cost.budget: ["USD:5.00", ...]`; decremented by `metric` `cost.*`     | Render budget gauge from `cost.budget.remaining` metric; surface `BudgetExhaustedException`            | Per-currency counters; check before every authority-bearing op; reject negative metric values; prefer `tool_result` form  | Additive                     |
| §9.4    | Subset rules extended         | (delegation)        | MUST        | child `cost.budget` ≤ parent remaining; child `expires_at` ≤ parent     | n/a (server-side rule)                                                                                 | Enforce on delegate accept; child inherits parent `expires_at` if child omits                                             | Additive                     |
| §11     | Trace span attributes         | (otel)              | SHOULD      | `arcp.lease.expires_at`, `arcp.budget.remaining`                        | OTel adapter sets attributes on session/job spans                                                      | Same                                                                                                                      | Additive                     |

## Three new error codes (§12)

| Code                          | Retryable | Java exception (proposed)            | When emitted                                                                                  |
| ----------------------------- | --------- | ------------------------------------ | --------------------------------------------------------------------------------------------- |
| `AGENT_VERSION_NOT_AVAILABLE` | false     | `AgentVersionNotAvailableException`  | `job.submit` with `name@version` where the version is not registered (§7.5).                  |
| `LEASE_EXPIRED`               | false     | `LeaseExpiredException`              | Any authority-bearing op attempted at or after `lease_constraints.expires_at` (§9.5).         |
| `BUDGET_EXHAUSTED`            | false     | `BudgetExhaustedException`           | Any authority-bearing op when a `cost.budget` counter is ≤ 0 (§9.6).                          |

All three carry `retryable: false`. The Java `ArcpException` sealed
hierarchy (decided in Phase 4) must encode this so a generic retry
helper cannot mask them; a `RetryableArcpException` /
`NonRetryableArcpException` split at the sealed boundary is the
cleanest mapping.

## Capability negotiation (§6.2) — Java surface implications

The intersection rule is the only place where v1.1 invents
business logic in addition to wire format. Java consequences:

1. **`hello` builder MUST be feature-aware.** A `ClientBuilder
   .features(Feature... f)` defaults to "all v1.1 features the SDK
   implements." Callers can prune (for tests, conformance harnesses,
   or interop with stricter runtimes).
2. **`Session` exposes negotiated features.** `Session
   .negotiatedFeatures(): Set<Feature>` — the intersection. Every
   feature-conditional public method consults this and throws
   `FeatureNotNegotiatedException` (a `RuntimeException`, not
   sealed under `ArcpException` — it's an SDK misuse error, not a
   protocol error).
3. **Runtime side mirrors.** `ArcpRuntime` advertises features at
   construction; the welcome handler computes the intersection and
   refuses to dispatch v1.1 messages outside the set.
4. **Feature enum, not strings.** Internal `enum Feature {
   HEARTBEAT, ACK, LIST_JOBS, SUBSCRIBE, LEASE_EXPIRES_AT,
   COST_BUDGET, PROGRESS, RESULT_CHUNK, AGENT_VERSIONS }`. Wire
   serialization owns the kebab/snake conversion; nothing else
   knows the strings.

## What is NOT in v1.1 (deferred)

Per spec "Changes from v1.0":

- Job pause / unpause — do not design API hooks for this in v1.1
  surfaces.
- Job priority and scheduling hints.
- Federation across runtimes.
- Streaming-token surface for LLM outputs (distinct from
  `result_chunk`).

The Java SDK MUST NOT pre-bake these into public types. Reserving
slots like `Job.priority()` "for later" leaks unstable surface.

## v1.0 → v1.1 client/runtime compatibility matrix

| Client | Runtime | Behavior                                                                                     |
| ------ | ------- | -------------------------------------------------------------------------------------------- |
| v1.0   | v1.0    | Baseline. Unchanged.                                                                         |
| v1.0   | v1.1    | Client sends no `features`; runtime advertises rich `agents` (v1.0 ignores extras per §5.1). |
| v1.1   | v1.0    | Client receives flat `agents`; treats it as "no version info"; emits bare names only.        |
| v1.1   | v1.1    | Full intersection. Either peer can disable any single feature.                               |

Java SDK MUST be tested in all four cells. Phase 7 (tests) owns the
matrix.

## Java-specific friction surfaced by the delta

- **§6.4 heartbeat scheduling** wants a wall-clock timer per
  session. Virtual threads on a `ScheduledExecutorService` is
  fine, but `StructuredTaskScope` doesn't include scheduling — a
  separate `Executors.newSingleThreadScheduledExecutor()` for
  heartbeat is unavoidable. Document the seam.
- **§6.5 ack** is per-session state mutating on the hot event
  path. Use an `AtomicLong` for `last_processed_seq` and
  rate-limit the emit; do NOT take a lock.
- **§7.6 subscribe** returning a `Flow.Publisher<Event>` is the
  obvious Java idiom but requires back-pressure across a
  WebSocket; the implementation should use `SubmissionPublisher`
  as the boundary and propagate `request(n)` to a credit counter
  on the wire if the runtime supports it (it does not in v1.1;
  drop-on-overflow with a logged warning is the v1.1 behavior).
- **§8.4 result_chunk** decoding to `byte[]` is the wrong default
  for a 30MB report — surface a `ResultStream` that supports
  `transferTo(OutputStream)` and `Flow.Publisher<ByteBuffer>`. Do
  not buffer in memory.
- **§9.5 expires_at** must be `Instant` everywhere internally;
  parsing must reject offsets other than `Z` and reject past
  values at the SDK seam, not at the wire.
- **§9.6 cost.budget** counter math on `double` is wrong. Use
  `BigDecimal` for currency arithmetic; the metric value comes in
  as a JSON number, parse with `BigDecimal(String)` from the raw
  token (Jackson's `USE_BIG_DECIMAL_FOR_FLOATS` feature, set at
  the SDK seam).
