# `arcp` — Java reference implementation of ARCP v1.0

A clean, idiomatic, tested Java reference implementation of the
**Agent Runtime Control Protocol (ARCP)** v1.0 as specified in
[RFC-0001-v2.md](./RFC-0001-v2.md).

> **Status:** Phase 0 (skeleton + plan). The Gradle multi-project build
> compiles cleanly and the four gate commands pass; no protocol surface is
> implemented yet. Track progress in [CONFORMANCE.md](./CONFORMANCE.md).

## Requirements

- **JDK 25** (LTS). Available via Homebrew as `openjdk` or by direct download
  from [adoptium.net](https://adoptium.net/) / [openjdk.org](https://openjdk.org/).
- **Gradle wrapper.** No system Gradle required; the wrapper bootstraps
  Gradle 9.0.0 on first run.

## Layout

```
java-sdk/
├── settings.gradle.kts        # multi-project: :lib, :cli, :examples
├── build.gradle.kts           # root config
├── gradle/libs.versions.toml  # version catalog
├── lib/                       # arcp library (Maven Central artifact)
├── cli/                       # `arcp` CLI binary (picocli, application plugin)
├── examples/                  # runnable example programs
├── PLAN.md                    # engineering plan
├── CONFORMANCE.md             # per-RFC-section status
└── RFC-0001-v2.md             # canonical protocol spec
```

## Build

```bash
export JAVA_HOME=/path/to/jdk-25     # e.g. /opt/homebrew/opt/openjdk
./gradlew check                      # compile + test + spotless + jacoco
./gradlew javadoc                    # publish-quality javadoc
./gradlew :lib:publishToMavenLocal   # local Maven artifact (Phase 7)
```

## Architecture

```mermaid
flowchart TB
  app[Application code]
  client[ARCPClient]
  runtime[ARCPRuntime]
  transport[Transport<br/>WebSocket | stdio | InMemory]
  store[(SQLite event log)]
  app --> client
  client --> transport
  transport --> runtime
  runtime --> store
```

- The **library** (`:lib`) is pure protocol: envelope serialisation, runtime,
  client, transports, event log.
- The **CLI** (`:cli`) is a thin picocli wrapper: `arcp serve|tail|send|replay`.
- The **examples** (`:examples`) are runnable demo programs: minimal session,
  tool invocation with progress, human input, permission challenge, observer
  subscription, agent relay.

See [PLAN.md](./PLAN.md) for the design rationale, message-type to record map,
state diagrams, open questions, and per-phase deliverables.

## Quickstart (Phase 0)

```bash
git clone <repo>
cd arpc/java-sdk
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew check                  # green
./gradlew :cli:run --args=""     # prints arcp-java 0.1.0-SNAPSHOT
```

End-to-end examples (`:examples:run01` … `:run06`) ship in Phase 7.

## License

MIT.
