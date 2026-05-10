# Conformance — ARCP Java SDK v0.1-WIP

Status as of Phase 2 (Gate 2 passed; Gates 3–7 pending).

| RFC § | Topic | Status |
|---|---|---|
| §6.1 | Envelope (all fields incl. idempotency_key, priority, extensions, correlation_id, causation_id) | **Implemented** |
| §6.2 | Message type registry | **Partial** — `ping`, `pong`, `ack`, `nack`, all 9 `session.*` types. Remaining job/stream/human/permission/subscription/artifact/telemetry types: deferred. |
| §6.3 | Command / result / event flow | **Partial** — `correlation_id` / `causation_id` plumbed through `Envelope`; runtime sets them on handshake replies. |
| §6.4 | Transport idempotency + logical idempotency | **Implemented** — SQLite `EventLog.append` (transport) + `EventLog.recordIdempotency` (logical). |
| §6.5 | Priority semantics | **Partial** — `Priority` enum + wire form. No fairness scheduler yet. |
| §7   | Capability negotiation | **Implemented** — `Capabilities` record, `CapabilityNegotiator.intersect`, required-capability rejection via session.rejected/UNIMPLEMENTED. |
| §8.1 | Four-step handshake | **Partial** — open → accepted / unauthenticated / rejected. Challenge/authenticate path not yet driven. |
| §8.2 | Auth schemes | **Partial** — `bearer` (StaticBearerValidator), `signed_jwt` (JwtValidator HS256), `none` (capability-gated). mTLS / OAuth2: deferred per scope. |
| §8.3 | Identity in session.accepted | **Implemented** — `Identity` record carried in payload. |
| §8.4 | Mid-session refresh | **Not yet wired** — record exists, runtime branch missing. |
| §8.5 | Eviction | **Not yet wired** — record exists. |
| §9   | Stateful sessions | **Partial** — runtime tracks principal/session_id/capabilities. Durable resume: deferred. |
| §10  | Jobs | **Deferred to Phase 3** |
| §11  | Streams | **Deferred to Phase 3** |
| §12  | Human-in-loop | **Deferred to Phase 4** |
| §13  | Subscriptions | **Deferred to Phase 5** |
| §15  | Permissions & leases | **Partial** — `LeaseExpiredException` / `LeaseRevokedException` exist. Lease manager: Phase 4. |
| §16  | Artifacts | **Deferred to Phase 5** |
| §17  | Observability | **Partial** — SLF4J wired; `trace_id`/`span_id`/`parent_span_id` on envelope. `StandardMetrics` constants: Phase 3+. |
| §18  | Error taxonomy | **Implemented** — `ErrorCode` enum (21 codes); `ARCPException` hierarchy; default retryability table. |
| §19  | Resumability | **Partial** — message-id replay via `EventLog.replay`. Checkpoint resume: deferred. |
| §21  | Extensions | **Implemented** — `ExtensionNamespace` validation + `ExtensionRegistry`. Unknown-message handling: dispatcher TODO. |
| §22  | Transports | **Partial** — `MemoryTransport` for in-process; WebSocket + stdio in Phase 6. |

## Explicit deferrals (out of scope for v0.1)

- HTTP/2, QUIC transports
- mTLS, OAuth2 auth schemes
- Sidecar binary stream frames
- §10.6 scheduled jobs
- §14 multi-agent delegation / handoff
- Workflow primitives
- §15.6 trust elevation
- Checkpoint-based resume
- Artifact GC beyond periodic sweep
- §12.3 quorum human-response policy
- Android targets
- GraalVM native-image build

## Test coverage

- 41 unit + integration tests, all green
- JaCoCo line coverage on `:lib`: 87.4%
- Spotless, javadoc (`-Werror`), Error Prone + NullAway all clean
- JPMS module-info complete for the implemented surface
