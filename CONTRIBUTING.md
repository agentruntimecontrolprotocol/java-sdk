# Contributing to io.agentruntimecontrolprotocol:arcp

Thanks for your interest in improving the Java SDK for ARCP. This
document covers how to report issues, propose changes, and get a change merged.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Where changes belong

ARCP is two things in two places, and a change belongs to exactly one of them:

- **The protocol** — the wire format, message semantics, lease rules, error
  taxonomy, feature flags. These live in the
  [specification repository](https://github.com/agentruntimecontrolprotocol/spec).
  If your idea changes what goes *on the wire* or what a conformant runtime must
  do, it is a spec change — open it there, not here. This SDK implements the
  spec; it does not define it.
- **This SDK** — how the protocol is expressed idiomatically in Java:
  bugs, ergonomics, performance, missing-but-specified features, docs, tests.
  Those belong here.

When in doubt, open an issue here and we'll redirect if it's really a protocol
question.

## The golden rule: conform, don't extend

A change to this SDK must keep it a faithful client of
[ARCP v1.1 (draft)](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
Concretely:

- **Don't invent wire behavior.** No envelope fields, event kinds, error codes,
  or feature flags that the spec doesn't define. If you need one, it's a spec
  proposal first.
- **Negotiate honestly.** Only advertise a feature flag in `session.hello` once
  the SDK actually implements it. The feature matrix in the README must match
  what the code negotiates — a row marked `Supported` is a promise.
- **Respect the semantics.** Sequence numbers stay gap-free and monotonic;
  `LEASE_EXPIRED` and `BUDGET_EXHAUSTED` stay non-retryable; the effective
  feature set is the intersection of client and runtime advertisements. Tests
  must not paper over a semantic the spec requires.
- **Stay layered.** This SDK controls runtimes. It does not expose tools (that's
  MCP) or export telemetry (that's OpenTelemetry). PRs that blur those layers
  will be asked to move the logic out.

## Reporting bugs

Open an issue with: the SDK version and Java version, the runtime you
connected to, a minimal reproduction (the smallest program that triggers it),
what you expected, and what happened. A failing test is the best possible bug
report. Wire-level traces (the envelopes exchanged) help enormously for protocol
behavior — redact any `auth.token` or provisioned-credential `value` first.

## Proposing a change

For anything beyond a small fix, open an issue describing the problem before
writing code, so we can agree on the approach. Small, focused PRs review faster
than large ones; if a change is big, say so early and we'll help break it down.

## Development setup

The build targets JDK 21 LTS (`--release 21`) and runs through Apache Maven
3.9+. CI runs on Temurin 21 and 25; either works locally. Install Maven (or use
your IDE's bundled copy), clone, and point `JAVA_HOME` at a JDK 21+:

```sh
git clone https://github.com/agentruntimecontrolprotocol/java-sdk.git
cd arpc/java-sdk
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; use your distro's equivalent
mvn -version                                       # confirm Maven 3.9+ on JDK 21+
```

The build is a Maven multi-module reactor (`arcp-core`, `arcp-client`,
`arcp-runtime`, the `arcp` umbrella, transport adapters, middleware, the TCK,
and example/recipe projects); `pom.xml` at the repo root is the canonical
`<modules>` list.

## Tests and conformance

Two layers must pass before a PR merges:

- **Unit tests** — this SDK's own suite:

  ```sh
  mvn verify
  ```

  `verify` compiles every module, runs Spotless, and executes the JUnit 5 +
  jqwik suites across all subprojects. Use `mvn -pl arcp-core test` (with
  `-am` to also rebuild upstream modules) to skip compilation of unrelated
  modules during iteration.

- **Conformance** — the SDK's behavior against the reference runtime. New
  protocol-facing code (session negotiation, event sequencing, lease handling,
  error mapping) needs a test that exercises the real exchange, not a mock that
  assumes the answer. The reusable conformance suite lives in `arcp-tck` as a
  JUnit 5 `@TestFactory` (`dev.arcp.tck.ConformanceSuite` + `TckProvider`);
  point it at any `Transport` pair — `MemoryTransport.pair()` for the in-process
  reference runtime, or a live WebSocket pair via `arcp-runtime-jetty` +
  `arcp-client`'s `WebSocketTransport.connect(uri)`. Run with
  `mvn -pl arcp-tck -am test`.

CI runs both on every PR. A PR that changes which feature flags the SDK
negotiates must also update the README feature matrix in the same change.

## Coding standards

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless)
configured with Google Java Format and unused-import removal. The same JDK 21
`javac` settings (`-Xlint:all`, `-parameters`, UTF-8) and Javadoc generation
that CI uses are applied to every library module via the parent POM:

```sh
mvn spotless:apply                # auto-format
mvn spotless:check                # verify (CI gate on JDK 21)
mvn verify                        # compile + lint warnings + tests
mvn javadoc:javadoc               # Javadoc for the published modules
```

The pinned Spotless 2.44.x uses a google-java-format build that can't run
on JDK 25's javac internals; pass `-Darcp.skip.spotless=true` when iterating
on JDK 25 locally. CI pins the Spotless gate to JDK 21.

Beyond formatting, the house style is captured in the existing code: records
for value types, sealed hierarchies (`Message`, `EventBody`, `ArcpException`)
for exhaustive dispatch, JSpecify `@Nullable` on anything that may return null,
`AtomicReference` / `AtomicLong` / `ConcurrentHashMap` for shared mutable state,
and a spec `§` citation in the Javadoc of public types that implement a
protocol concept.

Match the surrounding code. Public API changes need doc comments and an entry in
the changelog. Prefer clarity over cleverness in a library others build on.

## Commit and pull-request conventions

- Write focused commits with present-tense, imperative subjects
  (`add result_chunk reassembly`, not `added` / `adds`).
- Reference the issue a PR closes (`Closes #123`).
- Keep the PR description honest about scope and any spec sections touched.
- Rebase on the default branch and ensure CI is green before requesting review.
- Sign off your commits to certify the [Developer Certificate of Origin](https://developercertificate.org/):

  ```sh
  git commit -s -m "your message"
  ```

## Releases

Releases are cut by maintainers. The `release` GitHub Actions workflow is
dispatched manually with a version input; it builds, signs with the project
PGP key, publishes the `io.agentruntimecontrolprotocol:*` artifacts to Maven Central via the
`central-publishing-maven-plugin`, and pushes a `vX.Y.Z` tag.
Detailed operator steps live in [RELEASING.md](RELEASING.md). The SDK is
versioned with semantic versioning independently of the protocol version it
speaks; a protocol version bump is noted in the changelog when the negotiated
ARCP version changes.

## License

By contributing, you agree that your contributions are licensed under the
project's [Apache-2.0](LICENSE) license.
