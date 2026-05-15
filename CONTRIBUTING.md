# Contributing

Thanks for considering a contribution. This SDK tracks
[`draft-arcp-02.1.md`](../spec/docs/draft-arcp-02.1.md). Behavior changes that
diverge from the spec are out of scope unless they fix a clear interpretation
bug; please open an issue first to discuss.

## Prerequisites

- JDK 21+ (Temurin is what CI uses). Set `JAVA_HOME` accordingly.
- Gradle 9.x via the bundled wrapper — do not install Gradle separately.
- Graphviz on `PATH` if you intend to touch `docs/diagrams/`.

## Local checks

```bash
./gradlew build                      # compile + test all 10 modules
./gradlew :examples:cancel:run       # run any of the 10 examples
./gradlew :arcp-core:pitest          # opt-in mutation analysis
make -C docs/diagrams                # render SVGs after editing .dot
```

`./gradlew build` runs ~140 tasks and finishes in about a minute on a fresh
checkout. All 18 test suites must stay green; broken tests block the PR.

## Coding conventions

- **JDK 21 floor.** `--release 21` is set in the root build; bytecode must
  stay consumable on JDK 21 even though the toolchain may compile on 25.
- **No `--enable-preview`** on consumer-facing artifacts. `StructuredTaskScope`
  is preview in 21 and final in 25 with a different shape; the runtime uses
  plain virtual threads on `Executors.newVirtualThreadPerTaskExecutor()`.
- **Records over Lombok.** All value types are records; the canonical
  constructor enforces invariants.
- **Sealed types for taxonomies.** `Message`, `EventBody`, `ArcpException`
  are sealed so dispatch is exhaustive at compile time. New wire types or
  error codes touch the sealed permits list.
- **No `null` returns without `@Nullable`.** JSpecify is on the compile
  classpath; mark anything that may return null.
- **Thread safety on shared mutable state.** Counters use
  `AtomicReference` / `AtomicLong` with CAS loops; idempotency uses
  `ConcurrentHashMap`. Avoid `synchronized` on hot paths.
- **Spec § citations in Javadoc.** Every public type implementing a
  protocol concept should cite the matching spec section.

## Test discipline

- A change to runtime FSM logic requires a test that exercises the
  transition.
- A change to wire format requires an envelope round-trip assertion
  ([`EnvelopeRoundTripPropertyTest`](arcp-core/src/test/java/dev/arcp/core/EnvelopeRoundTripPropertyTest.java)
  covers most of it via jqwik).
- A change visible to `Lease.contains`, `BudgetCounters`, or
  `LeaseGuard.authorize` requires either a property or a focused unit test.

## Diagrams

Light and dark variants must remain structurally identical — only color hex
codes legitimately differ. The `diagrams` workflow re-runs `dot -Tsvg` against
every `.dot` source and fails the PR if a checked-in SVG diverges from the
fresh render. Edit both variants together; commit both `.dot` and rendered
`.svg`.

## Conformance

`CONFORMANCE.md` rows that flip from `Deferred` to `Implemented` MUST cite a
`path:LineNumber` for the load-bearing code, and the PR MUST include a test
that exercises that line. PRs that mark a row Implemented without an
exercising test will be rejected at review.

## Commit + PR style

- Imperative summaries: "Wire heartbeat watchdog into SessionLoop", not
  "Added heartbeat watchdog".
- One topic per PR — split mechanical refactors from behavior changes.
- Reference the issue number in the PR description if one exists.
- New public types or methods carry Javadoc and at minimum one test.

## Reporting bugs

Open a GitHub issue. Include:

- JDK version (`java --version`).
- Whether the bug reproduces under `MemoryTransport` (the in-process
  pair), and if not, which transport.
- A failing test or a minimal client + runtime snippet — the
  [`SmokeRoundTripTest`](arcp-client/src/test/java/dev/arcp/client/SmokeRoundTripTest.java)
  shape is a good template.

Security-sensitive reports follow [SECURITY.md](SECURITY.md) instead.
