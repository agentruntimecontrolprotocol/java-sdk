---
title: "arcp-tck"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-tck`

Technology Compatibility Kit. Reusable JUnit 5 conformance tests that verify
any `Transport` adapter against the ARCP spec.

## Dependency

```kotlin
testImplementation("dev.arcp:arcp-tck:1.0.0")
```

`arcp-tck` re-exports `arcp-core`, `arcp-client`, `arcp-runtime`, JUnit
Jupiter, and AssertJ as `api` dependencies so you only need one test-scope
declaration.

## What it tests

| Test | Spec §§ |
|---|---|
| Envelope round-trip via `session.welcome` | §5.1 |
| Capability intersection with feature subset | §6.2 |
| `job.submit` returns `job.accepted` with resolved agent | §7.1 |
| Idempotency key reuse returns same `job_id` | §7.2 |
| Idempotency key conflict yields `DUPLICATE_KEY` | §7.2 |
| Unknown `agent@version` yields `AGENT_VERSION_NOT_AVAILABLE` | §7.5 |
| `LogEvent` reaches the client's `events()` publisher | §8.2 |
| Provisioned credentials surfaced and revoked | §9.8 |

## Key classes

### `TckProvider`

SPI your adapter implements. Responsible for spinning up a runtime and
connecting a client:

```java
public interface TckProvider extends AutoCloseable {
    ArcpClient connect() throws Exception;

    // Optional — override to support provisioned-credential assertions:
    default ArcpClient connectWithProvisionedCredentials(
            CredentialProvisioner provisioner,
            CredentialRevocationStore store) throws Exception {
        throw new UnsupportedOperationException("...");
    }
}
```

### `ConformanceSuite`

Generates the full list of `DynamicTest` instances. Call from a
`@TestFactory` method:

```java
@TestFactory
Stream<DynamicTest> conformance() {
    return ConformanceSuite.dynamicTests(this::buildProvider).stream();
}
```

## Wiring in a test class

```java
class JettyConformanceTest {

    TckProvider buildProvider() throws Exception {
        ArcpRuntime runtime = ArcpRuntime.builder()
            .agent("tck-echo", "1.0.0", (input, ctx) ->
                JobOutcome.Success.inline(input.payload()))
            .agent("tck-log-emitter", "1.0.0", (input, ctx) -> {
                ctx.emit(LogEvent.info("hello from tck-log-emitter"));
                return JobOutcome.Success.inline(input.payload());
            })
            .verifier(BearerVerifier.allowAll())
            .build();

        ArcpJettyServer server = ArcpJettyServer.builder(runtime)
            .port(0)
            .build()
            .start();

        return new TckProvider() {
            @Override
            public ArcpClient connect() {
                WebSocketTransport ws = WebSocketTransport.connect(server.uri());
                return ArcpClient.builder(ws).bearer("any").build();
            }

            @Override
            public void close() throws Exception {
                server.close();
                runtime.close();
            }
        };
    }

    @TestFactory
    Stream<DynamicTest> conformance() {
        return ConformanceSuite.dynamicTests(this::buildProvider).stream();
    }
}
```

## Required agents

`ConformanceSuite` expects two agents registered in the runtime under test:

| Agent ref | Behaviour |
|---|---|
| `tck-echo@1.0.0` | Returns the submitted payload as the result |
| `tck-log-emitter@1.0.0` | Emits at least one `LogEvent` before returning |

Both agents must be registered; missing agents will cause the corresponding
tests to fail with `AGENT_VERSION_NOT_AVAILABLE`.

## Credential assertion opt-in

The `§9.8 provisioned credentials` test calls
`TckProvider.connectWithProvisionedCredentials(...)`. If your provider does not
override that method, the test is automatically skipped (`Assumptions.abort`)
rather than failing.

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.tck` | `ConformanceSuite`, `TckProvider` |
