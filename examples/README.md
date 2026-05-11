# ARCP Examples

Fourteen single-purpose codebases, each named for the protocol
primitive it demonstrates.

> **Illustrative, not runnable.** Each example imports from the
> in-repo `dev.arcp` SDK as if it were a published `dev.arcp:lib:1.0`
> artifact. Setup boilerplate (transport URL, identity, auth) is
> elided as `ARCPClient client = null; // transport, identity, auth elided`.
> LLM and framework calls live in tiny stub files
> (`Agents.java`, `Steps.java`, `Synth.java`, …) so the protocol code
> in `Main.java` is what you read.

## The fourteen

| Directory | Demonstrates | Spec |
|---|---|---|
| [`subscriptions/`](./src/main/java/dev/arcp/examples/subscriptions) | Three Observer clients on one session, three filters, three sinks. | §5, §13 |
| [`leases/`](./src/main/java/dev/arcp/examples/leases) | Lease-gated shell agent. Read leases coarse, write leases scoped. | §15.4–§15.5 |
| [`lease_revocation/`](./src/main/java/dev/arcp/examples/lease_revocation) | Per-table leases with `lease.revoked` / `lease.extended` mid-flight. | §15.5 |
| [`permission_challenge/`](./src/main/java/dev/arcp/examples/permission_challenge) | Two-party permission challenge — generator asks, reviewer holds veto. | §15.4, §6.4 |
| [`delegation/`](./src/main/java/dev/arcp/examples/delegation) | `agent.delegate` fan-out + `JobMux` to demux events by `job_id`. | §14, §6.4 |
| [`handoff/`](./src/main/java/dev/arcp/examples/handoff) | `agent.handoff` with transcript packed as an artifact, runtime fingerprint pinned. | §14, §16, §8.3 |
| [`heartbeats/`](./src/main/java/dev/arcp/examples/heartbeats) | Worker federation; heartbeat-loss reroute via `idempotency_key`. | §10.3, §6.4 |
| [`capability_negotiation/`](./src/main/java/dev/arcp/examples/capability_negotiation) | Capability-driven peer routing; standard `cost.usd` rollups. | §7, §17.3.1, §18.3 |
| [`resumability/`](./src/main/java/dev/arcp/examples/resumability) | **Actually crash and resume.** `Runtime.halt(137)` mid-flight; second invocation picks up at the next step. | §10, §19, §6.4 |
| [`reasoning_streams/`](./src/main/java/dev/arcp/examples/reasoning_streams) | `kind: thought` stream + a peer runtime that subscribes and delegates critiques back. | §11.4, §13, §14 |
| [`extensions/`](./src/main/java/dev/arcp/examples/extensions) | Custom `arcpx.sdr.*.v1` extension namespace with correct unknown-message handling. | §21 |
| [`human_input/`](./src/main/java/dev/arcp/examples/human_input) | `human.input.request` fanned across phone/email/Slack; first-wins resolution. | §12 |
| [`cancellation/`](./src/main/java/dev/arcp/examples/cancellation) | Cooperative `cancel` (terminate) vs `interrupt` (pause and ask). | §10.4–§10.5 |
| [`mcp/`](./src/main/java/dev/arcp/examples/mcp) | ARCP runtime fronting an MCP server: `tool.invoke` → MCP `call_tool`. | §20 |

## Conventions

- Java 25 toolchain, virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`)
  for the concurrent fan-out / event-loop patterns.
- `record` types for immutable payloads. `var` and `final` thoughtfully.
- Each example is one `Main.java` (the protocol code) + 0–2 sibling
  stubs named for what they elide (`Agents.java`, `Steps.java`,
  `Cheap.java`, `Synth.java`, `Work.java`, `Channels.java`,
  `Sql.java`, `Upstream.java`).
- `ARCPClient client = null; // transport, identity, auth elided`
  literally — transport, identity, and auth blocks are setup noise,
  not the point.
- Envelopes match RFC-0001 v2 exactly. Custom message types follow
  §21.1 `arcpx.<domain>.<name>.v<n>` naming.

## What's where in the SDK

- `dev.arcp.client.ARCPClient` — handshake driver.
- `dev.arcp.envelope.Envelope`, `dev.arcp.error.ErrorCode`,
  `dev.arcp.error.ARCPException` — wire primitives.
- `dev.arcp.transport.Transport` (and `MemoryTransport`) — transport
  abstraction.
- `dev.arcp.runtime.ARCPRuntime` — server side.

## Reading order

For a brisk tour: `subscriptions`, `leases`, `delegation`,
`resumability` (this one actually crashes and recovers),
`cancellation`, `extensions`, `mcp`. These seven exercise the bulk
of the protocol.

## Building

```bash
cd /Users/nficano/code/arpc/java-sdk
./gradlew :examples:compileJava
```

Each example carries its own `main` method; pick one with the
`mainClass` system property:

```bash
./gradlew :examples:run -PmainClass=dev.arcp.examples.subscriptions.Main
```
