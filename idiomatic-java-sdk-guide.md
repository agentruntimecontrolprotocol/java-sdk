# Idiomatic Java for Public SDKs

A working spec for Claude Code. Opinionated where the standard is silent.
Optimized for SDKs consumed by third parties, where every public symbol
is a forever-commitment. Baseline: Java 21 LTS.

## Core philosophy

- Treat every `public` symbol as a contract you cannot break for five years.
- Boring code beats clever code. Clever code becomes someone's 3 AM problem.
- Minimize the public surface. You can always add later; you cannot remove.
- Zero runtime dependencies is the goal. Each dep is a compatibility
  liability you ship to every consumer.
- The SDK is not a framework. No DI containers, no annotations the consumer
  must understand, no classpath scanning.

## API surface discipline

- Split into `api` (public) and `internal` packages. Enforce with JPMS
  (`module-info.java`) or ArchUnit tests. Nothing in `.internal.` is
  reachable from outside the module.
- Every public class is `final` unless explicitly designed for extension.
  Extension is a separate decision with its own `protected` contract.
- Public constructors are forbidden on stable API types. Expose static
  factories: `of`, `from`, `parse`, `valueOf`, `copyOf`, `newBuilder`,
  `empty`.
- No public fields. Records or accessors only.
- Public signatures use interfaces, not implementations. Return `List<T>`,
  not `ArrayList<T>`. Return `Map<K,V>`, not `HashMap<K,V>`.
- Method overload count per name: max 3 per class. Beyond that, use a
  builder or distinct method names.
- Parameter count: max 4. Beyond that, introduce a parameter record.
- Every public element carries `@since X.Y` in its Javadoc.

## Types and data

- DTOs and value types: `record`. No exceptions unless serialization
  control forces otherwise.
- Closed hierarchies: `sealed interface` with `record` or `final class`
  permits. Closed = exhaustive `switch` with no `default`.
- Builders: only when the constructor exceeds 4 args or has optional
  fields. Hand-roll them. Do not ship Lombok in SDK code (classpath
  conflicts, debugger pain, generated-source surprises for consumers).
- `equals` / `hashCode` / `toString`: free from records. For classes,
  generate all three together; never half-implement.
- All collection-returning methods return unmodifiable views:
  `List.copyOf(...)`, `Set.copyOf(...)`, `Map.copyOf(...)`.
- All collection-accepting parameters defensively copy at entry:
  `this.items = List.copyOf(items);`.

## Null and errors

- Public methods never return `null`. Use `Optional<T>` for absence,
  empty collections for empty results.
- Public methods never accept `null` silently. Validate at the door:
  `Objects.requireNonNull(arg, "arg");`.
- Annotate every package with `@NullMarked` (JSpecify). Public API is
  non-null by default; mark exceptions with `@Nullable`.
- Define one SDK exception root: `AcmeException extends RuntimeException`.
  Subclass for distinct failure categories the consumer must
  distinguish (`AcmeAuthException`, `AcmeRateLimitException`).
- Never throw `RuntimeException`, `Exception`, or `Throwable` directly.
- Checked exceptions only when the caller can plausibly recover and the
  recovery path is non-obvious. Default to unchecked.
- Never swallow exceptions. If you must, log at WARN with the throwable
  and a Javadoc-grade comment explaining why.
- Exception messages include the offending value when safe. Never log
  secrets, tokens, or PII.

## Concurrency

- Document thread-safety of every public type in Javadoc with
  `@implSpec`: either "Instances are immutable and thread-safe." or
  "Not thread-safe; external synchronization required."
- Prefer immutable types. Mutable shared state is the bug magnet.
- Never `synchronized(this)` or `synchronized(SomeClass.class)` on
  public types. Use a `private final Object lock = new Object();`.
- No `ThreadLocal` in public API unless you also expose cleanup.
- Async APIs return `CompletableFuture<T>`. Do not invent custom futures.
- Never invoke user-supplied callbacks while holding a lock.
- Virtual threads are fine for IO-bound work. Document any operation
  that pins a carrier thread.

## Dependencies and packaging

- Zero runtime deps is the target. Each addition needs written
  justification; shade what you cannot avoid.
- Logging: depend on `slf4j-api` only. Never bundle a log implementation.
- JSON: prefer Jackson if needed, isolate behind an interface you own
  so it can be shaded or swapped.
- `module-info.java` declares every export explicitly. No
  `requires transitive` unless you genuinely re-export a type in your
  public API.
- Pin the minimum JDK and document it. Multi-release JARs only if
  there is no other path.

## Modern Java to embrace (21+)

- Records — all data carriers.
- Sealed types — all closed hierarchies.
- Pattern matching for `switch` — dispatch on sealed hierarchies.
- Text blocks — embedded SQL/JSON/HTML literals.
- `var` — only when the type is obvious from the right-hand side on
  the same line. Never to hide a long inferred type from the reader.
- Streams — fine, but max 4 chained operations before naming an
  intermediate. A `for` loop is not a defeat.
- Virtual threads — fine, with carrier-pinning operations called out.

## Anti-patterns (do not ship these)

- Static mutable state.
- Singletons via private constructor + static field. Use plain
  dependency passing.
- Inheritance for code reuse. Compose.
- `Optional` as a parameter type or a field. Return type only.
- Wildcards in public return types (`List<? extends Foo>`). Be specific.
- Returning arrays where collections will do.
- Reflection in hot paths.
- Framework annotations (`@Inject`, `@Component`, `@Autowired`,
  `@Path`, `@JsonProperty`) on public types. The SDK is not opinionated
  about the consumer's framework.
- Lombok on public types.
- Public types in the default package.
- `instanceof` chains in place of sealed-type dispatch.

## Complexity budgets

Hard caps. Code that exceeds them is refactored, not negotiated.

- File length: **300 lines hard cap, 200 target.** Split by
  responsibility, not arbitrarily.
- Method length: **30 lines hard cap, 15 target.** Excluding Javadoc,
  signature, and braces.
- Class length: **200 lines hard cap** excluding nested records.
- Cyclomatic complexity per method: **10 hard cap, 5 target.**
- Cognitive complexity per method: **15 hard cap.**
- Parameter count: **4 hard cap.**
- Nesting depth: **3 hard cap.** Use early returns, guard clauses,
  extracted helpers.
- Line length: **aspire to 80 chars; 100 hard cap.** Wrap before
  operators; align method-chain dots at the dot.
- Public symbols per package: **20 soft cap.** Beyond that, the package
  is doing too much.

Rules of thumb when caps are approached:

- Method approaching length cap → extract a named private helper.
- File approaching length cap → the type is doing too much; split by
  responsibility (parser vs validator, model vs serializer).
- Parameter cap hit → introduce a parameter `record`.
- Nesting cap hit → invert with early `return`/`throw`.
- Cyclomatic cap hit → replace conditional chain with polymorphism or
  a sealed-type `switch`.

## Naming

- Classes: `PascalCase`, nouns. Avoid `Manager`, `Helper`, `Util`,
  `Service` in public API.
- Methods: `camelCase`, verbs. Booleans start with `is`, `has`, `can`,
  `should`.
- Static factories: `of`, `from`, `parse`, `valueOf`, `copyOf`,
  `newBuilder`, `empty`.
- Constants: `UPPER_SNAKE_CASE`. Only for `static final` immutable
  primitives or truly immutable values.
- Type parameters: single uppercase (`T`, `K`, `V`) or descriptive
  `PascalCase` ending in `T` (`RequestT`) when clarity beats brevity.
- Internal packages: include `.internal.` in the path. Document that
  everything inside is excluded from API guarantees.

## Javadoc

- Every public type, method, constructor, and field has Javadoc.
- First sentence is a complete summary ending with a period. Tools
  index it.
- Document `@param`, `@return`, `@throws` for every parameter, return,
  and declared exception.
- Use `@implSpec` for behavior subclasses or callers can rely on.
- Use `@apiNote` for usage guidance and gotchas.
- Use `@since` on every public element.
- Use `{@code ...}` for inline code, `{@link ...}` for symbol refs.
- HTML kept to `<p>`, `<pre>`, `<ul>`, `<li>`. Stay portable.

## Testing

- Public API: covered by integration tests in a separate module that
  consumes the published artifact, exactly as a third party would.
- Unit tests: JUnit 5 + AssertJ. No Hamcrest. No PowerMock.
- Never mock types you do not own. Wrap third-party deps behind an
  interface you own; mock the interface.
- Test names: `methodUnderTest_givenCondition_expectedOutcome`.
- One concept per test. Multiple `assertThat` lines are fine if they
  test the same concept.
- Property-based tests (jqwik) for parsers, codecs, and any pure
  function.
- No `Thread.sleep` in tests. Use Awaitility.

## Versioning and deprecation

- Strict SemVer. Any change to a `public` symbol's shape is a major
  version bump.
- Deprecate before remove. Minimum one minor version with
  `@Deprecated(since = "X.Y", forRemoval = true)` plus a migration note
  in Javadoc.
- Maintain `CHANGELOG.md` keyed by version with sections: Added,
  Changed, Deprecated, Removed, Fixed, Security.
- Run `revapi` or `japicmp` in CI on every PR. Breaking changes fail
  the build unless the PR is explicitly labeled `breaking`.

## Build hygiene

- Single source of truth for versions: a `libs.versions.toml` or
  equivalent.
- Reproducible builds: pin all plugin versions, fail on
  `latest.release`.
- Checkstyle, SpotBugs, PMD, ErrorProne (with NullAway in strict mode),
  ArchUnit, revapi/japicmp all wired into `check`. Failing checks fail
  the build. No warning-only gates.
