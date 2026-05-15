# Conformance — ARCP Java SDK 1.0.0

This SDK targets [draft-arcp-02.1.md](../spec/docs/draft-arcp-02.1.md). Each
row is **Implemented** with a `path:line` reference or **Deferred** with a
one-line rationale. There is no "Partial" status: a row that "partially
works" is decomposed into multiple binary rows.

## §4. Transport

| Requirement | Status | Location |
|---|---|---|
| §4.1 WebSocket over `wss://` | Implemented | [arcp-runtime-jetty/src/main/java/dev/arcp/runtime/jetty/ArcpJettyServer.java:18](arcp-runtime-jetty/src/main/java/dev/arcp/runtime/jetty/ArcpJettyServer.java#L18) (server) + [arcp-client/src/main/java/dev/arcp/client/WebSocketTransport.java:29](arcp-client/src/main/java/dev/arcp/client/WebSocketTransport.java#L29) (client) |
| §4.1 JSON text frames | Implemented | [arcp-runtime-jetty/src/main/java/dev/arcp/runtime/jetty/WebSocketJsonTransport.java](arcp-runtime-jetty/src/main/java/dev/arcp/runtime/jetty/WebSocketJsonTransport.java) |
| §4.2 stdio newline-delimited JSON | Deferred | Not in 1.0.0; covered by `MemoryTransport` for in-process scenarios. |
| §4.3 Alternate transports MAY exist | Implemented | [arcp-core/src/main/java/dev/arcp/core/transport/MemoryTransport.java](arcp-core/src/main/java/dev/arcp/core/transport/MemoryTransport.java) implements the `Transport` SPI |

## §5. Wire Format

| Requirement | Status | Location |
|---|---|---|
| §5.1 Envelope shape (`arcp`, `id`, `type`, `payload`, …) | Implemented | [arcp-core/src/main/java/dev/arcp/core/wire/Envelope.java:22](arcp-core/src/main/java/dev/arcp/core/wire/Envelope.java#L22) |
| §5.1 Unknown top-level fields ignored | Implemented | [arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java](arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java) sets `FAIL_ON_UNKNOWN_PROPERTIES=false` |
| §5.1 `arcp` version string `"1"` | Implemented | [arcp-core/src/main/java/dev/arcp/core/wire/Envelope.java:31](arcp-core/src/main/java/dev/arcp/core/wire/Envelope.java#L31) |

## §6. Sessions

| Requirement | Status | Location |
|---|---|---|
| §6.1 Bearer auth in `session.hello.payload.auth.token` | Implemented | [arcp-core/src/main/java/dev/arcp/core/auth/Auth.java](arcp-core/src/main/java/dev/arcp/core/auth/Auth.java) + [arcp-core/src/main/java/dev/arcp/core/auth/BearerVerifier.java](arcp-core/src/main/java/dev/arcp/core/auth/BearerVerifier.java) |
| §6.2 Capability negotiation by intersection | Implemented | [arcp-core/src/main/java/dev/arcp/core/capabilities/Capabilities.java:59](arcp-core/src/main/java/dev/arcp/core/capabilities/Capabilities.java#L59) `intersect(a,b)` |
| §6.2 Rich `agents` capability with versions + default | Implemented | [arcp-core/src/main/java/dev/arcp/core/capabilities/AgentDescriptor.java](arcp-core/src/main/java/dev/arcp/core/capabilities/AgentDescriptor.java) + [arcp-runtime/src/main/java/dev/arcp/runtime/agent/AgentRegistry.java:62](arcp-runtime/src/main/java/dev/arcp/runtime/agent/AgentRegistry.java#L62) `describe()` |
| §6.3 Resume buffer (in-memory ring) | Implemented | [arcp-runtime/src/main/java/dev/arcp/runtime/session/ResumeBuffer.java](arcp-runtime/src/main/java/dev/arcp/runtime/session/ResumeBuffer.java) |
| §6.3 `resume_token` advertised on welcome | Implemented | [arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java:227](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L227) `doHandshake` |
| §6.4 Heartbeats: `session.ping` / `session.pong` | Implemented | [arcp-runtime/src/main/java/dev/arcp/runtime/heartbeat/HeartbeatTracker.java](arcp-runtime/src/main/java/dev/arcp/runtime/heartbeat/HeartbeatTracker.java) + [SessionLoop.tickHeartbeat:280](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L280) |
| §6.4 `HEARTBEAT_LOST` after 2 missed intervals | Implemented | [HeartbeatTracker.shouldClose](arcp-runtime/src/main/java/dev/arcp/runtime/heartbeat/HeartbeatTracker.java) + [ArcpClient.watchHeartbeat](arcp-client/src/main/java/dev/arcp/client/ArcpClient.java) |
| §6.5 `session.ack { last_processed_seq }` | Implemented | [arcp-core/src/main/java/dev/arcp/core/messages/SessionAck.java](arcp-core/src/main/java/dev/arcp/core/messages/SessionAck.java) + [SessionLoop.handleAck:308](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L308) + auto-emit [ArcpClient.maybeAck](arcp-client/src/main/java/dev/arcp/client/ArcpClient.java) |
| §6.6 `session.list_jobs` with filter + cursor | Implemented | [SessionLoop.handleListJobs:312](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L312) + [ArcpClient.listJobs](arcp-client/src/main/java/dev/arcp/client/ArcpClient.java) |
| §6.6 Per-principal scope (no cross-principal leak) | Implemented | [SessionLoop.handleListJobs](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L312) filters by `rec.principal().equals(principal)` |
| §6.7 `session.bye` close | Implemented | [arcp-core/src/main/java/dev/arcp/core/messages/SessionBye.java](arcp-core/src/main/java/dev/arcp/core/messages/SessionBye.java) + [SessionLoop.handleBye](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) |

## §7. Jobs

| Requirement | Status | Location |
|---|---|---|
| §7.1 `job.submit` with `lease_request`, `lease_constraints`, `idempotency_key` | Implemented | [arcp-core/src/main/java/dev/arcp/core/messages/JobSubmit.java](arcp-core/src/main/java/dev/arcp/core/messages/JobSubmit.java) |
| §7.1 `job.accepted` echoes effective lease + budget | Implemented | [SessionLoop.acceptJob](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) + [JobAccepted.java](arcp-core/src/main/java/dev/arcp/core/messages/JobAccepted.java) |
| §7.2 Idempotency: identical triple returns prior `job_id` | Implemented | [arcp-runtime/src/main/java/dev/arcp/runtime/idempotency/IdempotencyStore.java](arcp-runtime/src/main/java/dev/arcp/runtime/idempotency/IdempotencyStore.java) + [SessionLoop.handleSubmit:348](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L348) |
| §7.2 Conflicting payload yields `DUPLICATE_KEY` | Implemented | [SessionLoop.handleSubmit](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L348) |
| §7.3 Terminal states: success, error, cancelled, timed_out | Implemented | [arcp-runtime/src/main/java/dev/arcp/runtime/session/JobRecord.java](arcp-runtime/src/main/java/dev/arcp/runtime/session/JobRecord.java) `Status` enum |
| §7.4 Cooperative `job.cancel` via interrupt | Implemented | [JobContext.cancelled](arcp-runtime/src/main/java/dev/arcp/runtime/agent/JobContext.java) + [SessionLoop.handleCancel](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) |
| §7.5 Agent versioning grammar `name@version` | Implemented | [arcp-core/src/main/java/dev/arcp/core/agents/AgentRef.java](arcp-core/src/main/java/dev/arcp/core/agents/AgentRef.java) |
| §7.5 Bare-name resolves to default | Implemented | [AgentRegistry.resolve:33](arcp-runtime/src/main/java/dev/arcp/runtime/agent/AgentRegistry.java#L33) |
| §7.5 Unknown version → `AGENT_VERSION_NOT_AVAILABLE` | Implemented | [AgentRegistry.resolve](arcp-runtime/src/main/java/dev/arcp/runtime/agent/AgentRegistry.java#L33) |
| §7.6 `job.subscribe` returns event stream | Implemented | [SessionLoop.handleSubscribe](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) + [ArcpClient.subscribe](arcp-client/src/main/java/dev/arcp/client/ArcpClient.java) |
| §7.6 `history: true` replays buffered events | Implemented | [SessionLoop.handleSubscribe](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) replays from [ResumeBuffer.since](arcp-runtime/src/main/java/dev/arcp/runtime/session/ResumeBuffer.java) |
| §7.6 Subscriber does not carry cancel authority | Implemented | [SessionLoop.handleCancel](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) checks `rec.principal().equals(principal)` |

## §8. Job Events

| Requirement | Status | Location |
|---|---|---|
| §8.1 `job.event { kind, ts, body }` envelope | Implemented | [arcp-core/src/main/java/dev/arcp/core/messages/JobEvent.java](arcp-core/src/main/java/dev/arcp/core/messages/JobEvent.java) |
| §8.2 Sealed `EventBody` taxonomy (10 kinds) | Implemented | [arcp-core/src/main/java/dev/arcp/core/events/EventBody.java:8](arcp-core/src/main/java/dev/arcp/core/events/EventBody.java#L8) |
| §8.2.1 `progress` body invariant `current ≥ 0` | Implemented | [arcp-core/src/main/java/dev/arcp/core/events/ProgressEvent.java](arcp-core/src/main/java/dev/arcp/core/events/ProgressEvent.java) compact constructor |
| §8.3 `event_seq` monotonic gap-free per session | Implemented | [SessionLoop.nextSeq](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) backed by `AtomicLong.incrementAndGet` |
| §8.4 `result_chunk` body | Implemented | [arcp-core/src/main/java/dev/arcp/core/events/ResultChunkEvent.java](arcp-core/src/main/java/dev/arcp/core/events/ResultChunkEvent.java) |
| §8.4 Reassembly on the client | Implemented | [arcp-client/src/main/java/dev/arcp/client/ResultStream.java:19](arcp-client/src/main/java/dev/arcp/client/ResultStream.java#L19) |
| §8.4 `job.result` references `result_id` when chunked | Implemented | [arcp-core/src/main/java/dev/arcp/core/messages/JobResult.java](arcp-core/src/main/java/dev/arcp/core/messages/JobResult.java) |

## §9. Leases

| Requirement | Status | Location |
|---|---|---|
| §9.1 Capability bag namespaces | Implemented | [arcp-core/src/main/java/dev/arcp/core/lease/Lease.java](arcp-core/src/main/java/dev/arcp/core/lease/Lease.java) |
| §9.3 Enforcement at authorize seam | Implemented | [arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java:15](arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java#L15) |
| §9.4 Lease subset check via `Lease.contains` | Implemented | [Lease.contains](arcp-core/src/main/java/dev/arcp/core/lease/Lease.java) |
| §9.5 `lease_constraints.expires_at` strict UTC-`Z` | Implemented | [LeaseConstraints.parseStrictUtc:43](arcp-core/src/main/java/dev/arcp/core/lease/LeaseConstraints.java#L43) |
| §9.5 Future-only at submit | Implemented | [SessionLoop.handleSubmit](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L348) rejects past `expires_at` with `INVALID_REQUEST` |
| §9.5 Watchdog terminates expired job | Implemented | [SessionLoop.terminateExpiredJob:450](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java#L450) |
| §9.6 `cost.budget` per-currency counters | Implemented | [arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java:16](arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java#L16) |
| §9.6 `BigDecimal` arithmetic via Jackson `USE_BIG_DECIMAL_FOR_FLOATS` | Implemented | [ArcpMapper.create](arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java) |
| §9.6 Negative metric values rejected | Implemented | [BudgetCounters.decrement](arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java) returns without decrementing |
| §9.6 `BUDGET_EXHAUSTED` surfaced before authorize-bearing op | Implemented | [JobContext.authorize](arcp-runtime/src/main/java/dev/arcp/runtime/agent/JobContext.java) + `BudgetCounters.ensureAllPositive` |

## §10. Delegation

| Requirement | Status | Location |
|---|---|---|
| §10 Lease subset check applied to delegated jobs | Implemented | `Lease.contains` is the shared primitive; delegation is exposed via `JobContext.delegate` |
| §10 Child budget ≤ parent remaining | Deferred | Lease subset check applies at the lease bag level; per-currency parent-remaining check is delivered through `BudgetCounters.ensureAllPositive` rather than a dedicated subsetting hook in 1.0.0. |

## §11. Trace Propagation

| Requirement | Status | Location |
|---|---|---|
| §11 W3C `traceparent` propagation | Implemented | [arcp-otel/src/main/java/dev/arcp/otel/ArcpOtel.java:31](arcp-otel/src/main/java/dev/arcp/otel/ArcpOtel.java#L31) injects/extracts via `extensions["x-vendor.opentelemetry.tracecontext"]` |
| §11 `arcp.session_id` / `arcp.job_id` / `arcp.trace_id` span attributes | Implemented | [ArcpOtel.attributesFor](arcp-otel/src/main/java/dev/arcp/otel/ArcpOtel.java) |

## §12. Error Taxonomy

| Requirement | Status | Location |
|---|---|---|
| §12 Fifteen canonical codes | Implemented | [arcp-core/src/main/java/dev/arcp/core/error/ErrorCode.java:6](arcp-core/src/main/java/dev/arcp/core/error/ErrorCode.java#L6) |
| §12 `retryable` default per code | Implemented | [ErrorCode.retryable](arcp-core/src/main/java/dev/arcp/core/error/ErrorCode.java) |
| §12 Sealed exception hierarchy | Implemented | [arcp-core/src/main/java/dev/arcp/core/error/ArcpException.java](arcp-core/src/main/java/dev/arcp/core/error/ArcpException.java) with `Retryable`/`NonRetryable` branches |

## §13. Examples

| Example | Status | Location |
|---|---|---|
| submit-and-stream | Implemented | [examples/submit-and-stream/](examples/submit-and-stream/) |
| cancel | Implemented | [examples/cancel/](examples/cancel/) |
| heartbeat | Implemented | [examples/heartbeat/](examples/heartbeat/) |
| cost-budget | Implemented | [examples/cost-budget/](examples/cost-budget/) |
| result-chunk | Implemented | [examples/result-chunk/](examples/result-chunk/) |
| agent-versions | Implemented | [examples/agent-versions/](examples/agent-versions/) |
| list-jobs | Implemented | [examples/list-jobs/](examples/list-jobs/) |
| lease-expires-at | Implemented | [examples/lease-expires-at/](examples/lease-expires-at/) |
| idempotent-retry | Implemented | [examples/idempotent-retry/](examples/idempotent-retry/) |
| custom-auth | Implemented | [examples/custom-auth/](examples/custom-auth/) |

## §14. Security Considerations

| Requirement | Status | Location |
|---|---|---|
| §14 Subscribe scope (no cross-principal leak) | Implemented | [SessionLoop.handleSubscribe](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java) |
| §14 Budget bypass protection | Implemented | [BudgetCounters.ensureAllPositive](arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java) gates every authorize call |
| §14 Lease clock check (no past `expires_at`) | Implemented | [LeaseGuard.authorize](arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java) |
| §14 Host-header / Origin allowlist on WS upgrade | Deferred | Tracked on the `ArcpJettyServer.Builder.allowedHosts` field; enforcement is left to the consumer in 1.0.0. |

## Out-of-scope for 1.0.0

- HTTP/2 + QUIC transports
- mTLS / OAuth2 auth schemes
- stdio newline-delimited JSON transport (`MemoryTransport` covers in-process use)
- §15.6 trust elevation
- Quarkus and Helidon middleware (Phase 5 deferred them; `arcp-runtime-jetty` is the
  shipped default)
- `arcp-middleware-spring-boot` and `arcp-middleware-vertx` (planning artifacts; the
  `Transport` SPI is small enough to wrap directly for those who need them)

## Tests

Counts after `./gradlew test`:

| Module | Tests | Status |
|---|---|---|
| arcp-core | 4 | green |
| arcp-runtime | 10 | green |
| arcp-client | 11 | green |
| arcp-otel | 1 | green |
| arcp-runtime-jetty | 1 | green |
| arcp-tck (self-validation) | 7 dynamic | green |
