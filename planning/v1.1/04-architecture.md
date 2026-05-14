# 04 — Architecture: module layout, type model, concurrency

Scope: Gradle subprojects, the record/sealed type model for the wire,
the virtual-thread concurrency seams, the error hierarchy, the
public API shape, and JPMS module-info. Implementation lives in
later phases; this file fixes the shape so those phases don't drift.

References: `../spec/docs/draft-arcp-02.1.md` §5.1 envelope, §6
sessions, §7 jobs, §8 events, §9 leases, §10 delegation, §11 traces,
§12 errors; `01-spec-delta.md`; `02-current-audit.md`; TS sources
under `../typescript-sdk/packages/{core,client,runtime,sdk}/src/`.

---

## 1. Gradle subproject layout

TS publishes four packages (`@arcp/core`, `@arcp/client`,
`@arcp/runtime`, `@arcp/sdk`). The Java SDK mirrors that split as
six Gradle subprojects, replacing the current single `:lib`
(`02-current-audit.md` §"Module layout (as-built)"). The `arcp-otel`
slot exists in `settings.gradle.kts` from day one even though Phase
5 fills it; downstream `requires` clauses are cheaper to write once.

| Subproject    | Purpose                                                       | TS counterpart                       |
| ------------- | ------------------------------------------------------------- | ------------------------------------ |
| `arcp-core`   | Wire types, capability negotiation, error decode. No I/O.     | `@arcp/core` (envelope, messages, transport types, errors) |
| `arcp-client` | `ArcpClient`, connect / submit / subscribe / list_jobs / ack. | `@arcp/client` (`client.ts`)         |
| `arcp-runtime`| `ArcpRuntime` / `ArcpServer`, session lifecycle, job dispatch, lease + budget enforcement. | `@arcp/runtime` (`server.ts`, `job.ts`, `lease.ts`) |
| `arcp`        | Umbrella; re-exports the three above.                         | `@arcp/sdk` (`index.ts` re-export only) |
| `arcp-otel`   | Phase 5 territory: span emission, `arcp.lease.expires_at`, `arcp.budget.remaining` (§11). | (none in TS yet)                     |
| `arcp-tck`    | Conformance harness consumed by downstream impls.             | (none in TS — recommend YES; see below) |

### Dependency edges

- `arcp-core`: `api` → `jackson-databind`, `jackson-datatype-jsr310`,
  `jspecify`, `slf4j-api`, `ulid-creator`. `implementation` →
  nothing (`02-current-audit.md` flags `json-schema-validator`,
  `nimbus-jose-jwt`, `sqlite-jdbc`, `Java-WebSocket` for drop;
  none belong in core).
- `arcp-client`: `api` → `arcp-core`. `implementation` → JDK
  `java.net.http.HttpClient` (WebSocket client; no extra dep).
- `arcp-runtime`: `api` → `arcp-core`. `implementation` → server
  WebSocket. Phase 3 chooses Jetty 12 vs Undertow; the choice
  lives behind the `Transport` sealed interface so the runtime
  package surface is unaffected.
- `arcp`: `api` → `arcp-client`, `api` → `arcp-runtime` (which
  transitively re-exports `arcp-core`). No own code beyond
  `module-info.java`.
- `arcp-otel`: `api` → `arcp-core`, `api` → `opentelemetry-api`.
  `implementation` → `opentelemetry-sdk` only for the autoconfigure
  helper; SDK pulls are opt-in.
- `arcp-tck`: `api` → `arcp-core`, `arcp-client`, `arcp-runtime`,
  `junit-jupiter-api`. Phase 7 consumes it via `testImplementation`.

### Justify merges and splits vs TS

- TS has no equivalent of `arcp-otel` or `arcp-tck`. Java publishes
  both because the JVM module ecosystem treats optional artifacts
  as the idiomatic way to keep transitive deps narrow (`requires
  static` semantics in JPMS, see §6 below).
- TS folds `messages/`, `state/`, `store/`, `transport/`, `util/`
  into one package. Java keeps them as sibling packages
  (`dev.arcp.core.messages`, `dev.arcp.core.transport`,
  `dev.arcp.core.state`, `dev.arcp.core.wire`,
  `dev.arcp.core.util`) inside `arcp-core` — TS subdirectories
  map to Java subpackages, not subprojects. The
  client/runtime/core boundary is the only one that gets
  artifact boundaries, matching TS.
- `:cli` and `:examples` from the current build stay outside this
  six-artifact set. Phase 6 owns examples; Phase 10 decides
  whether to ship `:cli` for v1.1.

### `arcp-tck` recommendation: yes

Spec §1 invites multiple implementations. A TCK published as a
Maven artifact (a set of JUnit 5 abstract test classes parameterized
by a `TckRuntimeProvider`) is the cheapest forcing function for
interop. Downstream impls extend the abstract classes and supply
their own `start()`/`stop()` hooks; the wire-level assertions ride
on `arcp-core` types so they cannot drift from the SDK's own view of
the spec. This costs one extra publication and zero new types in
the hot path. Recommend ship.

---

## 2. Type model

### Envelope (§5.1)

```
record Envelope(
    String arcp,                  // "1"; non-null
    MessageId id,                 // non-null
    String type,                  // non-null; "session.hello", "job.event", ...
    @Nullable SessionId sessionId,
    @Nullable TraceId traceId,
    @Nullable JobId jobId,
    @Nullable Long eventSeq,
    JsonNode payload              // non-null but MAY be empty object
)
```

Nullability per §5.1: `arcp`, `id`, `type`, `payload` are always
required on the wire — non-nullable. `session_id` is absent on
`session.hello` (no session exists yet), so nullable. `trace_id`
is optional per §11. `job_id` is required on job-scoped messages
(`job.event`, `job.result`, `job.error`) and absent on session
control messages — nullable at the envelope level; presence is
enforced by the message constructor that wraps the envelope
payload. `event_seq` is required only on `job.event` — nullable
here, validated at the message-type seam.

`payload` is held as `JsonNode` at the envelope level, then
decoded into a typed payload by the matching `Message` record.
This is the same shape as TS `envelope.ts`: an outer parse
proves the envelope is well-formed before the message-specific
decoder runs, so unknown `type` values are degraded to a logged
warning rather than killing the parse loop (§5.1 "unknown
top-level fields MUST be ignored" extends here by analogy).

### Message taxonomy (sealed)

```
sealed interface Message permits
    SessionHello, SessionWelcome, SessionPing, SessionPong,
    SessionAck, SessionListJobs, SessionJobs, SessionBye,
    JobSubmit, JobAccepted, JobEvent, JobResult, JobError,
    JobCancel, JobSubscribe, JobSubscribed, JobUnsubscribe { }
```

That's the v1.1 set: eight session messages (six pre-existing
plus `ping`/`pong`/`ack`/`list_jobs`/`jobs` from §6.4–§6.6) and
nine job messages (six from v1.0 plus `subscribe`/`subscribed`/
`unsubscribe` from §7.6). `Message` is sealed so a 17-arm switch
proves exhaustiveness at compile time — the runtime cannot
silently miss a new message kind when v1.2 adds one.

### Event kinds (sealed)

```
sealed interface EventBody permits
    LogEvent, ThoughtEvent, ToolCallEvent, ToolResultEvent,
    StatusEvent, MetricEvent, ArtifactRefEvent, DelegateEvent,
    ProgressEvent, ResultChunkEvent { }
```

`ProgressEvent` (§8.2) and `ResultChunkEvent` (§8.4) are the v1.1
adds. `JobEvent` envelopes carry `EventBody`. Sealed lets the
client's `Flow.Subscriber<Event>` pattern-match without an
`Object`-typed default arm.

### Dispatch snippet

```
JobOutcome dispatch(Message m, SessionState s) {
    return switch (m) {
        case JobSubmit js      -> jobs.accept(js, s);
        case JobCancel jc      -> jobs.cancel(jc, s);
        case JobSubscribe sub  -> jobs.subscribe(sub, s);
        case JobUnsubscribe u  -> jobs.unsubscribe(u, s);
        case SessionPing p     -> heartbeat.replyPong(p, s);
        case SessionPong p     -> heartbeat.recordPong(p, s);
        case SessionAck a      -> ack.update(a, s);
        case SessionListJobs l -> jobs.list(l, s);
        case SessionBye b      -> sessions.close(b, s);
        default -> throw new IllegalStateException("client-only msg: " + m);
    };
}
```

The `default` arm catches messages a runtime should never receive
(`SessionWelcome`, `JobAccepted`, etc.); sealed types restrict the
`default` to "violation by the peer," not "unknown message."
Compile-time exhaustiveness is the point.

### Jackson configuration

Configure once in `ArcpMapper.create()` (the existing
`dev.arcp.envelope.ARCPMapper` survives the rename per
`02-current-audit.md` §"What is salvageable"):

- `registerModule(new ParameterNamesModule())` — records bind via
  canonical constructors. `lib/build.gradle.kts:51` already passes
  `-parameters` to javac; keep that flag, otherwise record
  parameter names are erased and Jackson falls back to positional
  reflection.
- `disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` —
  §5.1 mandates ignoring unknown top-level fields. The default
  Jackson behavior throws; flip it.
- `enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)` —
  §9.6 budget arithmetic on `double` is wrong (`0.1 + 0.2` does
  not equal `0.3`). Forces `JsonNode.numberValue()` to return
  `BigDecimal` for floating-point tokens. Phase 7 property-tests
  budget math.
- `disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)` —
  §9.5 wants ISO-8601 `Z`.
- `registerModule(new JavaTimeModule())` — `Instant` for
  `expires_at`, `sent_at`, `received_at`.

---

## 3. Concurrency model

### Virtual threads as the primary executor

Per JEP 444 (final in JDK 21), virtual threads make per-job
threading free. The runtime owns one
`Executors.newVirtualThreadPerTaskExecutor()` shared by all
sessions; each `Job` runs on its own virtual thread named
`arcp-job-<jobId>`. The client side uses the same pattern for the
WebSocket reader. Blocking I/O on a virtual thread is the design;
it's how the JDK 21 networking stack expects to be used.

### `StructuredTaskScope`: JDK 21 preview or JDK 25 stable?

JEP 480 makes `StructuredTaskScope` a preview in JDK 21; JEP 505
finalizes it (with a different shape) in JDK 25. The current
toolchain pins JDK 25 (`02-current-audit.md` §"Toolchain"). The
v1.1 SDK should hold for the stable API: target JDK 21 bytecode
(`--release 21`) but use JDK 25 toolchain features only where
they cross the bytecode boundary without trapping consumers. For
`StructuredTaskScope` specifically: **do not** use the preview
API. Falling back to plain `ExecutorService.submit` plus
`Future.get`/`cancel` for the small number of fan-out sites the
runtime needs (parallel agent loading at startup; lease
constraint checks) keeps the artifact JDK 21-runtime-clean.
**Recommend: defer `StructuredTaskScope` until the SDK's minimum
runtime is JDK 25 LTS.**

### Cancellation seam

Wire-level `job.cancel` (§7.4) must translate to `Thread.interrupt()`
on the virtual thread running the agent. The seam:

- `JobManager` holds `ConcurrentHashMap<JobId, Future<JobOutcome>>`.
  Submit returns the `Future`; cancel calls `future.cancel(true)`.
- The agent's `JobContext.cancelled()` returns
  `Thread.currentThread().isInterrupted()`. Agents that ignore
  this still get interrupted on blocking I/O via standard JDK
  interrupt semantics; the contract is "check periodically."

### `ScopedValue` for per-request context

`ScopedValue` (JEP 446 preview in JDK 21, JEP 506 stable in JDK
25) carries `sessionId`, `traceId`, `jobId` down the call stack
without an explicit `JobContext` parameter — useful for SLF4J
MDC integration and for the OTel adapter in Phase 5. But it's
preview in JDK 21, and the SDK already needs to pass `JobContext`
explicitly to give agents the lease and emit hook. Carrying
context twice is the wrong default. **Recommend: do not use
`ScopedValue` in v1.1.** Push `sessionId`/`traceId` into MDC
via a thin `try (var mdc = MdcScope.of(...)) { ... }` wrapper
that uses `ThreadLocal` underneath. Revisit when JDK 25 LTS is
the floor.

### `subscribe` back-pressure (§7.6)

`ArcpClient.subscribe(JobId)` returns `Flow.Publisher<Event>`.
The runtime side bridges WebSocket reads into a
`SubmissionPublisher<Event>`; subscribers fan out via the JDK
`Flow.Subscriber` API. v1.1 has no wire credit
(`01-spec-delta.md` §"§7.6 subscribe"); the policy is
**drop-on-overflow with a `WARN` SLF4J log**, matching TS
`packages/client/src/client.ts`. `Flow.Subscription.request(n)`
is accepted and recorded but does not produce a wire frame —
this is documented in the `subscribe()` Javadoc so callers
don't expect end-to-end back-pressure. The dropped-event count
is exposed as a metric on `Session.diagnostics()` so observability
isn't a black box.

`SubmissionPublisher.submit(Event)` on the runtime side blocks
the producer when subscribers fall behind; this is acceptable
only because the producer is a virtual thread. A real-thread
producer would pin a carrier thread; that path is excluded
because the WebSocket reader is a virtual thread by construction.

### Heartbeats (§6.4)

`StructuredTaskScope` is fork/join; it does not schedule.
Heartbeats need wall-clock scheduling. Each `ArcpRuntime`
instance holds a single
`Executors.newScheduledThreadPool(1, factory)` where `factory`
produces virtual threads. Per-session state holds the next
`ScheduledFuture<?>`; sending or receiving any message resets
the timer; `SessionState.close()` cancels it.

This executor is intentionally separate from the virtual-thread
executor used for jobs. Scheduling overhead is bounded
(one timer per session, not per job) and the JDK does not
provide a virtual-thread-aware `ScheduledExecutorService` —
the `newScheduledThreadPool` workaround with a virtual-thread
factory is the documented JDK 21+ recipe.

---

## 4. Errors

### Sealed top

```
sealed abstract class ArcpException extends Exception
    permits RetryableArcpException, NonRetryableArcpException { }
```

Sealing at the top forces a binary classification on every code,
which makes a generic `retry(Supplier<T>)` helper provably
correct: it catches `RetryableArcpException` and rethrows
`NonRetryableArcpException`. No code path can sneak a
"retryable maybe" into the system.

### Concrete subclasses (§12, 15 codes)

| ErrorCode (wire string)         | Java class                            | Branch         |
| ------------------------------- | ------------------------------------- | -------------- |
| `PERMISSION_DENIED`             | `PermissionDeniedException`           | Non-retryable  |
| `LEASE_SUBSET_VIOLATION`        | `LeaseSubsetViolationException`       | Non-retryable  |
| `JOB_NOT_FOUND`                 | `JobNotFoundException`                | Non-retryable  |
| `DUPLICATE_KEY`                 | `DuplicateKeyException`               | Non-retryable  |
| `AGENT_NOT_AVAILABLE`           | `AgentNotAvailableException`          | Non-retryable  |
| `AGENT_VERSION_NOT_AVAILABLE`   | `AgentVersionNotAvailableException`   | Non-retryable  |
| `CANCELLED`                     | `CancelledException`                  | Non-retryable  |
| `TIMEOUT`                       | `TimeoutException`                    | Retryable      |
| `RESUME_WINDOW_EXPIRED`         | `ResumeWindowExpiredException`        | Non-retryable  |
| `HEARTBEAT_LOST`                | `HeartbeatLostException`              | Retryable      |
| `LEASE_EXPIRED`                 | `LeaseExpiredException`               | Non-retryable  |
| `BUDGET_EXHAUSTED`              | `BudgetExhaustedException`            | Non-retryable  |
| `INVALID_REQUEST`               | `InvalidRequestException`             | Non-retryable  |
| `UNAUTHENTICATED`               | `UnauthenticatedException`            | Non-retryable  |
| `INTERNAL_ERROR`                | `InternalErrorException`              | Retryable      |

§12 names `INTERNAL_ERROR` as "always retryable." `LEASE_EXPIRED`
and `BUDGET_EXHAUSTED` are explicitly `retryable: false`. `TIMEOUT`
and `HEARTBEAT_LOST` are connection-level and worth a retry from
the client with a new session. The rest are caller bugs or policy
denials; retry without changing the request is wrong.

### Decode path

`ErrorCodes.fromWire(String)` returns `ErrorCode` (an `enum`
implementing `Comparable`), letting downstream code switch on
the enum rather than the string. `ArcpException.from(ErrorCode,
JsonNode payload)` is a factory that builds the concrete
subclass and lifts the spec-defined error fields (`message`,
`retryable`, `details`) into typed accessors.

Per `01-spec-delta.md` §"Capability negotiation",
`FeatureNotNegotiatedException extends RuntimeException` is NOT
part of this sealed hierarchy. Misuse of the SDK is not a
protocol error.

---

## 5. Public API sketch

### `ArcpClient`

```
public final class ArcpClient implements AutoCloseable {
    public static Session connect(URI uri, Auth auth, ClientOptions opts);
    public JobHandle submit(JobRequest request);
    public Flow.Publisher<Event> subscribe(JobId id, SubscribeOptions opts);
    public Page<JobSummary> listJobs(JobFilter filter);
    public void ack(long eventSeq);
    @Override public void close();
}
```

`Auth` is a sealed interface (`BearerAuth`, `AnonymousAuth`).
`ClientOptions` is a record with `Duration connectTimeout`,
`Set<Feature> requestedFeatures`, `Executor virtualThreadExecutor`
(default = SDK-provided). `Session` is returned from `connect`
because the negotiated feature set is required before any other
call — see §1 in `01-spec-delta.md`.

### `ArcpRuntime` / `ArcpServer`

```
public final class ArcpRuntime implements AutoCloseable {
    public static Builder builder();
    public ArcpServer bind(InetSocketAddress address);
    @Override public void close();

    public static final class Builder {
        public Builder agent(String name, String version, Agent handler);
        public Builder features(Feature... advertised);
        public Builder leasePolicy(LeasePolicy policy);
        public ArcpRuntime build();
    }
}

public final class ArcpServer implements AutoCloseable {
    public InetSocketAddress address();
    public void awaitTermination();
    @Override public void close();
}
```

**`ArcpRuntime` and `ArcpServer` are separate types.** `ArcpRuntime`
is the configurable, transport-agnostic dispatcher (matches TS
`server.ts:ARCPServer`'s logical role); `ArcpServer` is the
bound, listening transport. One runtime can `bind` more than one
server (e.g., WebSocket plus an in-memory test transport) — TS
elides this because its server type owns the transport, but Java
benefits from the separation when tests want a memory-transport
runtime alongside a real one.

### `Transport`

```
public sealed interface Transport permits
    MemoryTransport, WebSocketClientTransport, WebSocketServerTransport {
    void send(Envelope envelope);
    Flow.Publisher<Envelope> incoming();
    void close();
}
```

`incoming()` returns `Flow.Publisher` rather than `receive(Duration):
Optional<Envelope>`. The `Optional`-returning variant forces every
consumer to spin a loop; the `Flow.Publisher` variant is the
natural shape for the runtime's existing
`SubmissionPublisher`-bridged dispatch. **Recommend `Flow`.** The
unit tests use `MemoryTransport` whose publisher is a thin
`SubmissionPublisher<Envelope>` wrapper.

### `Agent`

```
@FunctionalInterface
public interface Agent {
    JobOutcome run(JobInput input, JobContext ctx) throws Exception;
}
```

`JobContext` exposes `lease()` (the effective `Lease` record),
`emit(EventBody body)` (writes a `JobEvent` for this job),
`cancelled()` (interrupt check), and `delegate(JobRequest child)`
(§10). `JobInput` is `record JobInput(JsonNode payload,
SessionId sessionId, TraceId traceId, JobId jobId)`. `JobOutcome`
is a sealed interface (`JobSuccess(JsonNode result)`,
`JobError(ErrorCode code, String message)`); agents that stream a
result via `result_chunk` (§8.4) return a `JobSuccess` whose
`result` references a `result_id`, mirroring §8.4's terminating
`job.result`.

### `Session`

```
public final class Session implements AutoCloseable {
    public SessionId sessionId();
    public Set<Feature> negotiatedFeatures();
    public Optional<String> resumeToken();
    public Duration heartbeatInterval();
    public List<AgentDescriptor> availableAgents();
    @Override public void close();
}
```

Opaque handle, immutable except for `resumeToken` which rotates
on each `session.welcome` (§6.3). The `AtomicReference<String>`
behind `resumeToken()` is the only mutable field.

### `Job` / `JobHandle`

The runtime side has `Job` (an internal class managing the
virtual thread, lease state, and event sequence counter). The
client side gets `JobHandle`:

```
public final class JobHandle {
    public JobId jobId();
    public String resolvedAgent();      // "name@version" per §7.5
    public Lease effectiveLease();
    public Flow.Publisher<Event> events();
    public CompletableFuture<JobResult> result();
    public void cancel();
}
```

**`CompletableFuture<JobResult>` rather than the virtual thread's
`Future`.** A virtual-thread `Future` is local to the runtime;
client-side, the "future" is a logical completion driven by a
wire-level `job.result` or `job.error`. `CompletableFuture`
gives consumers `thenCompose`/`orTimeout`/`exceptionally`
without leaking the runtime's threading model. The cost is one
allocation per job, which is negligible against the WebSocket
round-trip.

### Nullability

JSpecify (`org.jspecify.annotations.@Nullable`,
`@NonNull`). The `02-current-audit.md` confirms `jspecify` is
already a dependency. JSR-305 is dormant and conflicts with the
NullAway preset already in `lib/build.gradle.kts`. **Confirm
JSpecify.** Package-level `@NullMarked` makes non-null the
default; explicit `@Nullable` marks the exceptions.

---

## 6. JPMS module-info

Recommend ON. The existing SDK has a `module-info.java` per
`02-current-audit.md`; keeping JPMS preserves the `requires
transitive` chain and stops consumers from accidentally pulling
runtime internals into client code.

```
module dev.arcp.core {
    requires transitive com.fasterxml.jackson.databind;
    requires transitive com.fasterxml.jackson.datatype.jsr310;
    requires transitive org.slf4j;
    requires transitive org.jspecify;
    requires com.github.f4b6a3.ulidcreator;
    exports dev.arcp.core.wire;
    exports dev.arcp.core.messages;
    exports dev.arcp.core.events;
    exports dev.arcp.core.error;
    exports dev.arcp.core.capabilities;
    exports dev.arcp.core.auth;
    exports dev.arcp.core.ids;
    exports dev.arcp.core.lease;
    exports dev.arcp.core.transport;
    opens dev.arcp.core.wire     to com.fasterxml.jackson.databind;
    opens dev.arcp.core.messages to com.fasterxml.jackson.databind;
    opens dev.arcp.core.events   to com.fasterxml.jackson.databind;
    opens dev.arcp.core.lease    to com.fasterxml.jackson.databind;
}

module dev.arcp.client {
    requires transitive dev.arcp.core;
    requires java.net.http;
    exports dev.arcp.client;
}

module dev.arcp.runtime {
    requires transitive dev.arcp.core;
    exports dev.arcp.runtime;
}

module dev.arcp {
    requires transitive dev.arcp.client;
    requires transitive dev.arcp.runtime;
}
```

Every package that defines a record consumed by Jackson needs
`opens X to com.fasterxml.jackson.databind`. `02-current-audit.md`
§"High-risk items" item 6 calls this out — the demolition will
add new modules, and missing `opens` clauses produce silent
deserialization failures (Jackson falls back to reflection and
throws `InaccessibleObjectException` at runtime, not compile
time). The `opens ... to` form keeps reflective access scoped to
Jackson rather than world-readable.

---

## 7. Hard rules

- **No `@Inject` / Spring / DI framework** anywhere in
  `arcp-core`, `arcp-client`, or `arcp-runtime`. Consumers wire
  agents via `ArcpRuntime.Builder.agent(...)`. TS does it this
  way (`runtime/src/server.ts`); Java follows.
- **No Lombok** in any module. Records cover the value-object
  use case; `@Builder` is replaced by explicit static `builder()`
  methods on the few types that need them (`ArcpRuntime`,
  `JobRequest`).
- **No `--enable-preview` at runtime.** Per §3, the SDK
  intentionally avoids `StructuredTaskScope` (preview in JDK 21)
  and `ScopedValue` (preview in JDK 21) until JDK 25 is the
  consumer floor. Bytecode target is `--release 21`
  (`02-current-audit.md` §"Toolchain"). Build toolchain stays
  free to advance.

---

## Risks

1. **Jackson `opens` clauses missed at module split.** The
   demolition splits one JPMS module into four. Every package
   containing a record needs an `opens ... to
   com.fasterxml.jackson.databind` line. Mitigation: an
   `arcp-tck` test that round-trips one record per package
   catches missing opens at JUnit time, not at downstream
   integration.
2. **`SubmissionPublisher` producer pinning if WebSocket reader
   is a real thread.** The drop-on-overflow policy is correct
   only when the producer is a virtual thread; otherwise a slow
   subscriber pins a carrier thread and stalls dispatch.
   Mitigation: assert at runtime startup that the configured
   executor is a virtual-thread executor (`Thread#isVirtual` on
   a sample); fail fast if not.
3. **JDK 21 floor vs `--release 21` bytecode skew.** `lib/build
   .gradle.kts:42` currently sets `release` to 25. Phase 3 must
   change this to 21 to keep the artifact consumable on JDK 21
   LTS. If left at 25, the SDK ships unusable to its target
   audience — silent in CI (which runs JDK 25), loud for users.
4. **`Flow.Publisher<Event>` back-pressure is one-sided.** TS
   chose drop-on-overflow; Java matches. A subscriber that calls
   `request(1)` and expects rate-limited delivery will be
   surprised when events still arrive at wire speed up to the
   buffer cap. Mitigation: spell it out in `subscribe()` Javadoc
   plus the `Session.diagnostics()` dropped-event counter.
5. **JSpecify + NullAway interaction with sealed hierarchies.**
   NullAway 0.10+ understands JSpecify, but exhaustive `switch`
   on a sealed type with a `@Nullable` operand still warns. The
   convention is `Objects.requireNonNull` at the dispatch entry,
   then exhaustive switch on the non-null value.
6. **`arcp-tck` becomes a dependency hairball.** A TCK that
   pulls in JUnit transitively forces every consumer to deal
   with JUnit versioning. Mitigation: `arcp-tck` declares
   `junit-jupiter-api` as `compileOnly` plus a `requires static`
   in `module-info.java`, leaving the test-runtime choice to
   the consumer.
