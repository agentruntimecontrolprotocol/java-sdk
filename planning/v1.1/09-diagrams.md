# 09 — Diagrams

Graphviz `.dot` sources under `docs/diagrams/`, paired SVGs committed
beside them, light/dark variants for GitHub auto-switching via the
`<picture>` element. The TypeScript SDK already ships this pattern in
`typescript-sdk/diagrams/`; the Java SDK adopts the same palette,
filenames-with-variant-suffix convention, and rendering policy so that
contributors switching repos see one visual language.

This phase is a plan — no `.dot` files are written here. Phase 10
sequences the actual landing of these files alongside the destination
code they document.

## 1. Diagram set

All under `docs/diagrams/`. Source is Graphviz `.dot`. Build target is
`dot -Tsvg <file>-light.dot -o <file>-light.svg` (and `-dark` variant).
Each diagram earns its slot by either citing a spec § it teaches OR
illustrating a Java-specific seam that prose alone obscures.

| Filename                                                | Kind            | Contents                                                                                                                                                                                                                              | Earns its slot because                                                  | Spec §                  |
| ------------------------------------------------------- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | ----------------------- |
| `module-graph-{light,dark}.dot`                         | directed DAG    | Gradle subprojects: `arcp-core`, `arcp-client`, `arcp-runtime`, `arcp`, `arcp-otel`, `arcp-middleware-jakarta`, `arcp-middleware-spring-boot`, `arcp-tck`. Arrows distinguish `api` (solid) vs `implementation` (dashed) dependencies.   | Java-specific. Mirrors the Phase 4 split decision and the JPMS boundary. | (build, Phase 4)        |
| `session-lifecycle-{light,dark}.dot`                    | FSM             | States: `connecting`, `hello_sent`, `welcomed`, `resuming`, `active`, `closing`, `closed`. Transitions labeled with message types (`session.hello`, `session.welcome`, `session.close`, transport-drop). `HEARTBEAT_LOST` as a dashed-red error transition from `active` → `closed`. | Mirrors §6 directly; the spec narrates the states in prose only.          | §6.1–§6.7, §6.4         |
| `job-lifecycle-{light,dark}.dot`                        | FSM             | States: `pending`, `running`, `success`, `error`, `cancelled`, `timed_out`. v1.1-new paths: `running` → `error` via `LEASE_EXPIRED` and `BUDGET_EXHAUSTED` (dashed-red, pale-yellow target sub-state on the error reason). Subscribe shown as an external observer arrow, not a state transition.   | Spec narrates §7.3 but never draws it; v1.1 adds two new error reasons that should be visible. | §7.3, §7.6, §9.5, §9.6  |
| `capability-negotiation-{light,dark}.dot`               | sequence        | Client `session.hello { features: [...] }` → Runtime `session.welcome { features: [...] }`. A side box shows the intersection set both peers compute. Annotated with a concrete v1.1 feature-flag example (`heartbeat`, `ack`, `subscribe`, `result_chunk`).                                          | Spec §6.2 introduces the intersection rule; Java consequence is the `Feature` enum and `Session.negotiatedFeatures()` surface from `01-spec-delta.md`.  | §6.2                    |
| `heartbeat-ack-{light,dark}.dot`                        | sequence        | Idle peer sends `session.ping { nonce, sent_at }`. Receiver replies `session.pong { ping_nonce, received_at }` within `heartbeat_interval_sec`. Two-missed-interval rule → `HEARTBEAT_LOST` (dashed-red). Then a separate `session.ack { last_processed_seq }` track showing rate-limited emission.   | Two v1.1 features (§6.4, §6.5) sharing one sequence keeps the "what the wall-clock looks like" story in one place; the Java `ScheduledExecutorService` seam is annotated as a cluster on the client side.   | §6.4, §6.5              |
| `result-chunk-{light,dark}.dot`                         | sequence        | Agent emits `result_chunk` events with `chunk_seq` 0..N-1 (`more: true`), final `result_chunk` (`more: false`), then `job.result { result_id, result_size }`. Client side annotated with `Flow.Subscriber.request(n)` back-pressure into a `SubmissionPublisher` boundary; reassembly note cites `chunk_seq` ordering.                                                 | §8.4 is the most Java-friction-heavy v1.1 feature (see `02-current-audit.md` high-risk #2); the diagram pairs the wire shape with the JDK `Flow` mechanics so reviewers see both at once.   | §8.4                    |

### Diagrams considered and rejected

| Candidate                                       | Why not                                                                                                                                                                                                                                              |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Envelope class diagram                          | The `Envelope` record is six fields; Javadoc tells the same story. Class diagrams of records are anti-pattern slop.                                                                                                                                  |
| `Capabilities` record class diagram             | Same problem. The interesting part is the `features: Set<Feature>` intersection rule, already covered by `capability-negotiation`.                                                                                                                   |
| Error-code hierarchy tree                       | Phase 4 owns the sealed `ArcpException` taxonomy; it's a list, not a graph. A bullet list in Phase 4 docs is clearer than a tree diagram with 15 leaves.                                                                                            |
| Per-message-type schema diagram                 | Phase 8 (wire-format prose) and the JSON Schema sources in `spec/` own this. Re-drawing each message in dot adds maintenance burden and decays against the spec.                                                                                    |
| Lease delegation diagram                        | §9.4 subset rules are server-side enforcement; the client SDK doesn't surface delegation differently from a fresh lease. No Java-specific seam to illustrate.                                                                                       |
| Resume-token state diagram                      | `session-lifecycle` already shows the `resuming` state and the transitions in/out. A dedicated diagram would just zoom in on one transition.                                                                                                         |
| OTel span tree                                  | Phase 5 owns OTel mapping. A span tree is data, not architecture — better expressed as a table of `arcp.*` attributes keyed by span.                                                                                                                 |
| Job list pagination flow                        | §6.6 list-jobs is a request/response with a cursor. One paragraph of prose. No state to draw.                                                                                                                                                       |

The six retained diagrams are the floor and ceiling. If a seventh
proposal arrives, it must displace one of these.

## 2. Style conventions

These conventions are stated once here, copied verbatim into the header
of each `.dot` source. Every diagram MUST follow them.

### Layout

- `rankdir=LR` for sequence diagrams and the module graph.
- `rankdir=TB` for finite state machines (`session-lifecycle`, `job-lifecycle`).
- `bgcolor="transparent"` in both light and dark variants — sits on
  whatever page background the viewer has.
- `compound=true` so edges can attach to cluster boundaries via
  `lhead` / `ltail`.
- `splines=spline`, `nodesep=0.32`, `ranksep=0.55`, `pad="0.35,0.25"`.

### Typography

- `fontname="Inter, Helvetica, sans-serif"` (Inter first, Helvetica as
  the fallback Graphviz actually finds in CI runner images, `sans-serif`
  as last resort).
- `fontsize=11` for node labels; `fontsize=9` for edge labels;
  `POINT-SIZE="12"` (HTML-label) for anchor nodes.

### Node defaults

- `shape=box, style="rounded,filled", margin="0.22,0.11", penwidth=1.0`.
- Light: `fillcolor="white", color="#CBD5E1", fontcolor="#1F2937"`.
- Dark: `fillcolor="#334155", color="#475569", fontcolor="#F1F5F9"`.
- Data stores: `shape=cylinder` (used in `module-graph` for any
  durable component, e.g. an opt-in `EventLog`).

### Highlight rules

- **Two anchors max per diagram.** One ENTRY (blue `#3B82F6` / `#2563EB`)
  for the initiating peer or entry point, one HUB (amber `#F59E0B` /
  `#D97706`) for the central authority. If you'd highlight a third
  thing, none of them are highlighted.
- **v1.1-new states and messages** are filled pale yellow:
  `style="rounded,filled", fillcolor="#FFF7D6", color="#E0C97A"`. This
  is layered ON TOP of the anchor scheme (an anchor that is also v1.1-new
  stays anchor-colored; the cluster label notes "_new in 1.1_" instead).
  In the dark variant: `fillcolor="#4A4118", color="#7A6A3A"`.
- **Error transitions** use dashed red with red label:
  `color="#C0392B", fontcolor="#C0392B", style=dashed, penwidth=1.1`.
  In the dark variant: `color="#F87171", fontcolor="#F87171"`. Color
  is never the only signal — the dashed style carries equal weight for
  accessibility.

### Edges

Two-tier wiring switched mid-graph as in the TS templates:

- **Primary spine** (happy path / main flow): light `color="#64748B",
  penwidth=1.2`; dark `color="#94A3B8", penwidth=1.2`.
- **Secondary wiring** (supporting concerns, alt paths): light
  `color="#CBD5E1", penwidth=1.0`; dark `color="#475569", penwidth=1.0`.
- **Feedback / async return** (e.g. `result_chunk` back to client):
  dashed pink `color="#F472B6", style=dashed, penwidth=1.1,
  constraint=false`. Label uses pink-600 in light, pink-400 in dark.

### Spec-section anchoring

Every transition that maps to a wire message MUST cite the spec section
on the label. Examples:

```dot
Pending -> Running [label="job.accepted\n§7.1"];
Running -> Error   [label="LEASE_EXPIRED\n§9.5", color="#C0392B",
                    fontcolor="#C0392B", style=dashed];
```

The `\n` keeps the section anchor on its own line and stops it from
ballooning edge widths. If a transition has no spec § (e.g. internal
SDK state, build-time `api` vs `implementation`), the label states
that explicitly — never an unlabeled arrow.

### Anti-color-only rule

A reader with deuteranopia must still distinguish:

- Error transitions (dashed, red) from happy-path (solid, slate).
- v1.1-new nodes (pale yellow fill, but ALSO labeled "_new_" in the
  cluster header where it lives) from v1.0 nodes (white/slate fill).
- ENTRY (blue, bold label) from HUB (amber, bold label) from default
  (no bold).

Bold weight, dash style, and shape carry the signal; color is
redundant reinforcement, not the primary channel.

### Light / dark variants

Both variants MUST be structurally identical — same nodes, same edges,
same cluster boundaries, same labels. Only color attributes differ.
This is the same rule the TS repo enforces, and the CI drift detector
(below) is what catches divergence.

### Rendering policy

- Source: `<name>-light.dot` and `<name>-dark.dot` under `docs/diagrams/`.
- Output: `<name>-light.svg` and `<name>-dark.svg` committed alongside.
- CI re-runs `dot -Tsvg` against every `.dot` and fails if the freshly
  rendered SVG differs from the checked-in SVG (byte diff, after a
  stable-order pass since Graphviz output is deterministic at a fixed
  version). This is the drift detector.
- Graphviz version pinned in CI to the runner-image default (Ubuntu
  24.04 ships Graphviz 2.42.x; if the runner image bumps to 12.x in
  2026, the pin moves with it and SVGs are re-rendered in the same
  PR). The CI workflow sets `apt-get install -y graphviz=<pinned>`
  and captures `dot -V` in the build log for forensics.

## 3. Build wiring

Recommendation: **plain `make` under `docs/diagrams/`**, not a Gradle
task.

Rationale:

- The docs site (Phase 8) is not a Gradle consumer. Forcing diagram
  rendering into the Gradle graph couples docs builds to the JVM
  toolchain for no benefit.
- Gradle adds ~3 seconds of startup for a `dot` invocation that takes
  ~50ms; the dev loop matters.
- `make %.svg: %.dot` is one line and every CI image has `make`.
- Gradle integration loses the "diagram drift detector" — running the
  task is opt-in and easy to forget; a `Makefile` rule is run by
  `ci.yml` unconditionally.

The `docs/diagrams/Makefile` skeleton (illustrative, not the actual
file):

```make
DOTS := $(wildcard *.dot)
SVGS := $(DOTS:.dot=.svg)

all: $(SVGS)

%.svg: %.dot
	dot -Tsvg $< -o $@

clean:
	rm -f $(SVGS)

.PHONY: all clean
```

CI runs `make -C docs/diagrams` and then `git diff --exit-code
docs/diagrams/*.svg` — the second command fails the build if rendering
produced a diff against the committed SVGs.

For developers without Graphviz installed: a `make check` target
prints "Graphviz not on PATH; install with `brew install graphviz` or
`apt-get install graphviz`" and exits 0 (advisory, not blocking,
since editing prose elsewhere in `docs/` shouldn't require Graphviz).
CI does NOT skip — the runner image has `dot`, so missing-tool there
is a real failure.

If reviewers later want a Gradle entry point for IDE integration, a
thin `tasks.register("renderDiagrams", Exec::class) { commandLine("make",
"-C", "docs/diagrams") }` wrapper is trivial and adds no logic of its
own — keep it as a one-line shim, not the source of truth.

## 4. Out of scope

- UML class diagrams. Records carry their semantics in Javadoc; a
  `record Envelope(...)` is its own diagram.
- Per-message-type schema diagrams. The spec is the schema; Phase 8
  owns wire-format prose. Re-drawing each `session.*` and `job.*`
  message would duplicate the spec and decay against it.
- Animation, interactivity, embedded JS. Static SVG only. GitHub
  serves SVG inline; the `<picture>` element handles theme switching;
  nothing else is needed.
- Sequence diagrams for the v1.0 baseline. These exist in the TS repo
  and the spec already; Java docs reference, not re-render, them. The
  six diagrams above all illustrate either v1.1 deltas (the four
  sequence/FSM ones) or Java-specific build structure (`module-graph`).
- Per-IDE screenshot of the project layout. The Gradle module graph
  is the authoritative answer.

## 5. Anti-slop guardrails

These apply to every `.dot` label, comment, cluster title, and to
prose in any future README adjacent to the diagrams.

### Banned words

The following words MUST NOT appear in diagram labels, cluster titles,
or surrounding markdown prose:

- "leverage" — substitute: "use".
- "robust" — substitute: a specific property the thing actually has.
- "scalable", "performant", "powerful" — say what it does, not how it
  feels.
- "modern" — every diagram is modern when committed; the word is empty.
- "enterprise-grade", "battle-tested", "production-ready" — marketing,
  not engineering.

### Earn-your-slot rule

Every diagram has been justified above with one of:

- A spec § it teaches (FSMs mirror §6/§7; sequences mirror §6.2/§6.4/§6.5/§8.4).
- A Java-specific seam it illustrates that survives no other format
  (the Gradle module graph; the `ScheduledExecutorService` cluster on
  `heartbeat-ack`; the `Flow.Subscriber.request(n)` cluster on
  `result-chunk`).

A diagram that would look identical for the TypeScript SDK AND restates
prose already in `docs/` is rejected. The TS repo's
`session-handshake-light.dot` is, by this rule, NOT re-drawn here —
the Java equivalent earns its slot only by adding the Java-side
`Session.negotiatedFeatures()` annotation, which is why the diagram is
named `capability-negotiation`, not `session-handshake`.

### Mechanical sanity checks

For each `.dot`:

- Open it. Count anchor nodes (blue + amber). MUST be ≤ 2.
- Count edges with no label. MUST be 0 except where layout demands
  invisible alignment edges (`style=invis`).
- Grep for "§". Every wire-message-carrying edge must match.
- Diff light against dark with `diff -u <name>-light.dot
  <name>-dark.dot | grep -v -E '#[0-9A-Fa-f]{6}'`. Output MUST be
  empty — only color hex codes legitimately differ.

## Length and review

Reading target: ≤8 minutes. This document is the plan; the `.dot`
files arrive in the Phase 10 demolition + landing sequence, paired
with the destination code each diagram teaches:

- `module-graph` lands with the Phase 4 module split.
- `session-lifecycle`, `capability-negotiation`, `heartbeat-ack` land
  with the Phase 4 handshake + v1.1 control surface.
- `job-lifecycle`, `result-chunk` land with the Phase 4 / 5 jobs +
  streaming surface.

No `.dot` file lands ahead of the code it documents.
