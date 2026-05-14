# 05 — Host Adapters (middleware) for the Java SDK

The TypeScript SDK ships six middleware packages
(`packages/middleware/{node,express,fastify,hono,bun,otel}`). Most of
those exist because the Node ecosystem has many incompatible HTTP
servers; the Java ecosystem has fewer, larger ones. This document
picks the Java analogs, defends each choice, and specifies the
"host attachment seam" — how a connected `WebSocket` gets handed to
`ArcpRuntime` along with an authenticated principal and a
Host-header check.

Spec anchors: §4 (WebSocket transport mandatory for network
deployments), §6.1 (Bearer token in `session.hello.payload.auth.token`),
§14 (Security: subscription scope, lease clock, budget bypass,
result-chunk size, heartbeat amplification — and v1.0 §14
Host/Origin guidance carried forward).

---

## 1. Adapter inventory and decision

| TS adapter | Java equivalent                              | Decision | Why                                                                                                                          |
| ---------- | -------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `node`     | `arcp-middleware-jakarta` (Jakarta WebSocket on Servlet 6) | Keep, rename | The TS `node` package is the seam (`attachArcpUpgrade`) every other TS host wraps. Jakarta WebSocket plays the same role for the JVM. |
| `express`  | `arcp-middleware-spring-boot` (WebMvc/Tomcat) | Keep, replace target | Spring Boot is the JVM's Express by deployment volume; both add convenience over the raw seam. |
| `fastify`  | (no analog)                                  | Drop     | Fastify is Node-native; its niche (low-overhead, schema-first Node HTTP) does not exist in Java. Helidon SE and Quarkus occupy adjacent niches but are not Fastify ports. |
| `hono`     | (no analog)                                  | Drop     | Hono targets edge runtimes (Workers, Deno Deploy, Bun). The closest JVM shape is Vert.x, but Vert.x is not an edge runtime — see §1 defensible-adds below. |
| `bun`      | (no analog)                                  | Drop     | Bun is a JS runtime, not a Java host. GraalVM native-image is the JVM "alt-runtime" story; it consumes whichever server adapter the user picks. |
| `otel`     | `arcp-otel`                                  | Keep     | Required for parity — see §3. |

### Required Java adapters

- **`arcp-middleware-jakarta`** — Jakarta WebSocket 2.2 on a Servlet 6
  container, with `jakarta.security.auth.message` (Jakarta
  Authentication / JASPIC) feeding the `Principal` into the
  `ArcpRuntime.accept(...)` call. Wraps `jakarta.websocket.Endpoint`
  (or `@ServerEndpoint`). This is the JVM analog of TS `node` — every
  other JVM adapter we accept wraps it the way `@arcp/express`
  wraps `@arcp/node`.
- **`arcp-middleware-spring-boot`** — Spring Boot 3.4+, **WebMvc**
  (Tomcat) only. Picking WebMvc over WebFlux: Spring's WebFlux
  WebSocket surface (`WebSocketHandler.handle(WebSocketSession)`)
  forces a `Flux<WebSocketMessage>` programming model that fights
  the SDK's `Transport` SPI (which is callback-shaped — `onFrame`,
  `send(frame)`). WebMvc's Tomcat-backed `@ServerEndpoint` is a
  thin facade over Jakarta WebSocket; reusing `arcp-middleware-jakarta`
  underneath is straightforward. Consumers who run WebFlux can
  still use the SDK — they just call `ArcpRuntime.accept(transport)`
  themselves and skip the adapter. Spec §4 mandates WebSocket, not
  a reactor.
- **`arcp-otel`** — see §3.

### Defensible adds

**Vert.x — accept (`arcp-middleware-vertx`).** Eclipse Vert.x 5 has
a non-trivial JVM audience that deliberately avoids both Servlet and
Spring. The `HttpServer.webSocketHandler(ServerWebSocket)` seam is
clean and Vert.x's event-loop concurrency model fits an event-driven
protocol. Cost is one extra subproject and a `vertx-core` test
matrix; the seam is small. **Yes.**

**Helidon SE 4 — defer.** Helidon SE 4 is virtual-thread-native and
appeals to the "no reactor, no Servlet" crowd. Its WebSocket API
(`WsRouting`, `WsListener`) is small enough to wrap cheaply, but
the audience overlap with Jakarta and Spring Boot consumers is
high, and Helidon SE is the smallest of the three by deployment
share. **Not in the v1.1 first cut; revisit if a user asks.**

**Quarkus extension — defer.** Quarkus has opinionated WebSocket
handling (`@ServerEndpoint` plus `quarkus-websockets-next` with its
own annotation surface). A first-class Quarkus extension would mean
mirroring those annotations rather than wrapping Jakarta; that's a
bigger surface than the Jakarta adapter and only pays off if there
is demand. Quarkus users can use `arcp-middleware-jakarta` directly
on `quarkus-websockets` today. **Not in the v1.1 first cut.**

**Netty raw — reject.** Jakarta (Undertow/Tomcat), Spring (Tomcat),
and Vert.x all sit on Netty already. Exposing raw Netty as a
supported surface duplicates everything below it. Users writing
their own Netty pipeline already control the `Channel` and can
call `ArcpRuntime.accept(transport)` themselves against a
`NettyWebSocketTransport` in `arcp-runtime`.

### Rejected (redundant or unmaintained)

| Host | Reason |
| ---- | ------ |
| `javax.websocket` (JSR-356) | Pre-Jakarta namespace, legacy; Jakarta WebSocket 2.x covers it under the `jakarta.*` package per the Java EE → Jakarta migration. |
| Spring 5 / Boot 2.x | Out of OSS LTS; Boot 3.x is the floor (matches `02-current-audit.md` JDK 21 floor decision). |
| Spring WebFlux (as the default Spring adapter) | Reactor types leak through the WebSocket handler API. WebMvc reuses Jakarta WebSocket directly; one default is enough. |
| Tyrus standalone | Jakarta WebSocket reference implementation; only relevant inside a Servlet container, which `arcp-middleware-jakarta` already targets. |
| Akka HTTP | Apache 2 licensed but the commercial steward (Lightbend) ships their own license terms for Akka itself; transitive risk is not worth the audience size. |
| Atmosphere | Legacy, low maintenance, and pre-Jakarta-namespace. |

---

## 2. Adapter specifications

### 2.1. arcp-middleware-jakarta

**Maven coordinate (proposed):** `dev.arcp:arcp-middleware-jakarta:1.1.0`
**Depends on:** `arcp-runtime` + `jakarta.websocket:jakarta.websocket-api:2.2.0` (compile) + `jakarta.servlet:jakarta.servlet-api:6.1.0` (compile) — both `compileOnly` so the container provides them at runtime.
**Host SDK version floor:** Jakarta WebSocket 2.2 (Jakarta EE 11), Jakarta Servlet 6.1, JDK 21.

**WebSocket upgrade attachment seam.** The adapter exposes
`ArcpJakartaEndpoint extends jakarta.websocket.Endpoint`. `onOpen(Session,
EndpointConfig)` wraps the `Session` in a
`JakartaWebSocketTransport` (implementation lives in
`arcp-runtime`; the adapter only adapts) and calls
`ArcpRuntime.accept(transport, principal)`. The principal is read
from `Session.getUserPrincipal()` (populated by the container's
Jakarta Authentication / `HttpServletRequest.getUserPrincipal()`
chain at upgrade time). The TS analog is `attachArcpUpgrade`'s
`onTransport(transport, req)` callback at
`typescript-sdk/packages/middleware/node/src/index.ts:36`; the
Java seam carries the principal instead of the raw request so
callers do not re-implement auth extraction.

**Host-header / DNS rebind defense (§14, v1.0 §14 carried forward).**
The adapter registers a
`jakarta.websocket.server.ServerEndpointConfig.Configurator`
override of `checkOrigin(originHeaderValue)` AND validates the
`Host` header out of the `HandshakeRequest` headers in
`modifyHandshake(...)`. If either check fails, `modifyHandshake`
sets a sentinel attribute on the user properties; `onOpen` then
closes with `CloseReason.CloseCodes.VIOLATED_POLICY` (1008). Config
knob: `arcp.middleware.allowedHosts: List<String>` (and
`allowedOrigins: List<String>`). Default-deny when the list is
configured; when absent, the SDK logs a `WARN` at startup naming
the unsafe default (so it shows up in production logs, not just
the docs).

**API sketch (≤15 lines):**

```java
ArcpJakartaAdapter adapter = ArcpJakartaAdapter.builder()
    .runtime(arcpRuntime)
    .path("/arcp")
    .allowedHosts(List.of("agents.example.com"))
    .allowedOrigins(List.of("https://app.example.com"))
    .build();

// Programmatic registration (Servlet 6 ServerContainer):
ServerContainer container =
    (ServerContainer) ctx.getAttribute(ServerContainer.class.getName());
container.addEndpoint(adapter.serverEndpointConfig());
```

**Open questions / non-goals.** This adapter does NOT bundle a
thread-pool config helper; consumers configure the container
executor. It does NOT integrate with Spring Security — the
Spring adapter handles that. The OAuth2/OIDC resource-server flow
is out of scope; the runtime sees a `Principal` and trusts it.

---

### 2.2. arcp-middleware-spring-boot

**Maven coordinate (proposed):** `dev.arcp:arcp-middleware-spring-boot:1.1.0`
**Depends on:** `arcp-runtime` + `arcp-middleware-jakarta` (transitive seam) + `org.springframework.boot:spring-boot-starter-web:3.4.+` (compileOnly) + `org.springframework.boot:spring-boot-starter-websocket:3.4.+` (compileOnly).
**Host SDK version floor:** Spring Boot 3.4, Spring Framework 6.2, JDK 21.

**WebSocket upgrade attachment seam.** Auto-configuration class
`ArcpSpringBootAutoConfiguration` registers a `ServerEndpointExporter`
(if none) and a singleton `ArcpJakartaAdapter` bean built from
`@ConfigurationProperties("arcp.middleware")`. The Spring layer's
job is property binding plus Spring-Security-aware principal
extraction: when `SecurityContextHolder` holds an `Authentication`,
the adapter prefers it over the container `Principal` so the
session sees the same identity Spring Security sees. The TS analog
is `createArcpExpressApp` +`attachArcpToExpress` at
`typescript-sdk/packages/middleware/express/src/index.ts:24`; the
shape is identical (one factory, one attach) but Spring binds via
config properties so the typical user writes zero code.

**Host-header / DNS rebind defense (§14).** Two layers:

1. The wrapped `ArcpJakartaAdapter` performs the WebSocket-handshake
   check.
2. A `WebMvcConfigurer` registers an interceptor against the same
   path so non-WebSocket GETs to `/arcp` (probes, health-check
   misconfig) are rejected with `403` when the `Host` header is
   not in the allowlist. This matches what `@arcp/express`'s
   `hostHeaderGuard` does at the Express layer.

Config knob: `arcp.middleware.allowed-hosts` (and
`allowed-origins`) as a YAML list. Default-deny when configured;
adapter refuses to start when `arcp.middleware.allowed-hosts` is
empty AND the active Spring profile is named `prod` or `production`
(fail-fast at boot, not at first request).

**API sketch (≤15 lines):**

```yaml
arcp:
  middleware:
    path: /arcp
    allowed-hosts: [agents.example.com]
    allowed-origins: [https://app.example.com]
```

```java
@Configuration
class ArcpConfig {
  @Bean ArcpRuntime arcpRuntime(ArcpRuntimeProperties p) {
    return ArcpRuntime.builder().agents(p.agents()).build();
  }
}
```

**Open questions / non-goals.** No starter for WebFlux in v1.1.
No actuator endpoint (`/actuator/arcp`) — defer to a separate
`arcp-spring-boot-actuator` if anyone asks. The adapter does
NOT register Spring-Security filters; users wire `HttpSecurity` to
permit the upgrade path themselves.

---

### 2.3. arcp-middleware-vertx

**Maven coordinate (proposed):** `dev.arcp:arcp-middleware-vertx:1.1.0`
**Depends on:** `arcp-runtime` + `io.vertx:vertx-core:5.0.+` (compileOnly).
**Host SDK version floor:** Vert.x 5, JDK 21.

**WebSocket upgrade attachment seam.** The adapter exposes
`ArcpVertxHandler implements Handler<ServerWebSocket>`. The
caller registers it via
`HttpServer.webSocketHandler(ArcpVertxHandler)`. The handler wraps
the `ServerWebSocket` in a `VertxWebSocketTransport` (in
`arcp-runtime`) and dispatches to `ArcpRuntime.accept(transport,
principal)`. Principal comes from a user-supplied
`Function<ServerWebSocket, Principal>` (Vert.x has no built-in
auth context attached to the socket; users typically run a
`Router` ahead of the upgrade for auth and stash the principal
on the routing context). The TS analog is the same
"`onTransport` callback per accepted socket" pattern as
`typescript-sdk/packages/middleware/bun/src/index.ts:42`.

**Host-header / DNS rebind defense (§14).** The handler inspects
`ServerWebSocket.headers().get("Host")` before completing the
handshake and calls `reject(403)` (then `close()`) on mismatch.
Config knob: `ArcpVertxHandler.builder().allowedHosts(List<String>)`.
Default-deny when set.

**API sketch (≤15 lines):**

```java
ArcpVertxHandler handler = ArcpVertxHandler.builder()
    .runtime(arcpRuntime)
    .path("/arcp")
    .allowedHosts(List.of("agents.example.com"))
    .principalResolver(ws -> ws.routingContext().user())
    .build();

vertx.createHttpServer()
    .webSocketHandler(handler)
    .listen(7777);
```

**Open questions / non-goals.** This adapter does NOT bundle a
`Router`-based REST surface. Vert.x clustering and the event bus
are out of scope; one transport per `ServerWebSocket` is the
seam, nothing more.

---

## 3. arcp-otel — parity with `@arcp/middleware-otel`

**Maven coordinate (proposed):** `dev.arcp:arcp-otel:1.1.0`
**Depends on:** `arcp-runtime` (for the `Transport` SPI) + `io.opentelemetry:opentelemetry-api:1.+` (api scope). **Explicitly NOT** `opentelemetry-sdk` — the consumer chooses an SDK and exporters.
**Host SDK version floor:** OpenTelemetry API 1.x, JDK 21.

**Seam.** Same shape as the TS package
(`typescript-sdk/packages/middleware/otel/src/index.ts:57`,
`withTracing(inner: Transport, { tracer }): Transport`): wrap a
`Transport` so each outbound `send` produces a span and each
inbound frame produces a span. The Java entry point is
`ArcpOtel.withTracing(Transport inner, Tracer tracer): Transport`.

**Attributes (parity table — exact names from TS source).** The
TS adapter sets these attributes
(`typescript-sdk/packages/middleware/otel/src/index.ts:142-181`).
The Java adapter MUST set the same names:

| TS attribute name              | Source line in TS otel index | v1.1 spec § | Java type                |
| ------------------------------ | ---------------------------- | ----------- | ------------------------ |
| `arcp.direction`               | 144 (`"in"` / `"out"`)       | n/a         | `String`                 |
| `arcp.type`                    | 147                          | §5.1        | `String`                 |
| `arcp.id`                      | 148                          | §5.1        | `String`                 |
| `arcp.session_id`              | 149                          | §6          | `String` (was `arcp.session.id` in some drafts — use the TS name `arcp.session_id` for wire parity) |
| `arcp.job_id`                  | 151                          | §7          | `String`                 |
| `arcp.trace_id`                | 152                          | §11         | `String`                 |
| `arcp.event_seq`               | 154                          | §8          | `long`                   |
| `arcp.agent`                   | 160                          | §7.5        | `String` (may include `@version`) |
| `arcp.lease.capabilities`      | 164                          | §9          | `String` (CSV of cap keys) |
| `arcp.lease.expires_at`        | 170                          | §9.5 / §11  | `String` (ISO 8601 `Z`)  |
| `arcp.budget.remaining`        | 177                          | §9.6 / §11  | `String` (JSON-encoded per-currency record, matching TS) |

**W3C `traceparent` propagation.** Outbound: `propagation.inject`
is the TS approach. The Java analog uses
`io.opentelemetry.context.propagation.TextMapPropagator.inject`
to populate a `Map<String,String>` carrier, then writes that
carrier into `envelope.extensions["x-vendor.opentelemetry.tracecontext"]`
(the namespace string is the constant `OTEL_EXTENSION_NAME` at
`typescript-sdk/packages/middleware/otel/src/index.ts:48`).
Inbound: the adapter calls `propagation.extract(Context.current(),
carrier, getter)` to derive a parent context, then
`tracer.spanBuilder(name).setParent(parent).startSpan()`. Same
namespace, same shape, same set of attribute names.

**Strict dependency rule.** The published POM declares
`io.opentelemetry:opentelemetry-api` only — no `-sdk`, no
`-exporter-*`. Consumers choose the SDK and exporters. This
matches `@opentelemetry/api` as the only runtime dep of the TS
package.

**Open questions / non-goals.** No metrics instrumentation in
v1.1. No log signal. No automatic resource-attribute population
(service.name, etc.) — that's the SDK's job. The adapter does
NOT register a global tracer provider.

---

## 4. Rejected hosts (one-line each)

- **`javax.websocket` (JSR-356)** — pre-Jakarta namespace; Jakarta WebSocket covers it.
- **Spring 5 / Boot 2.x** — out of OSS LTS; Boot 3.x is the floor.
- **Spring WebFlux as the default Spring adapter** — Reactor types in the WebSocket handler API fight the `Transport` SPI; one Spring default is enough.
- **Tyrus standalone** — Jakarta WebSocket RI; only relevant inside a Servlet container which the Jakarta adapter targets.
- **Akka HTTP** — licensing risk on the broader Akka project outweighs the audience.
- **Atmosphere** — legacy, low maintenance, pre-Jakarta namespace.
- **Netty raw** — Jakarta, Spring, and Vert.x all sit on Netty; the runtime's `NettyWebSocketTransport` is enough for users writing their own pipeline.

---

## 5. Build layout impact

Phase 4 Gradle subprojects to add (each a separate `:lib`-style
subproject):

```text
:arcp-middleware-jakarta
:arcp-middleware-spring-boot
:arcp-middleware-vertx
:arcp-otel
```

Dependency sketches (Kotlin DSL, one line per subproject — the
real build will resolve versions via `libs.versions.toml`):

```kotlin
// arcp-middleware-jakarta/build.gradle.kts
dependencies {
    api(project(":arcp-runtime"))
    compileOnly("jakarta.websocket:jakarta.websocket-api")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
}

// arcp-middleware-spring-boot/build.gradle.kts
dependencies {
    api(project(":arcp-runtime"))
    api(project(":arcp-middleware-jakarta"))
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-websocket")
}

// arcp-middleware-vertx/build.gradle.kts
dependencies {
    api(project(":arcp-runtime"))
    compileOnly("io.vertx:vertx-core")
}

// arcp-otel/build.gradle.kts
dependencies {
    api(project(":arcp-runtime"))
    api("io.opentelemetry:opentelemetry-api")
    // Explicitly NOT opentelemetry-sdk.
}
```

Every subproject keeps the host SDK at `compileOnly` (or `api` only
for the OTel API, which consumers cannot avoid having in their
classpath) so the SDK does not pin a host version into the
consumer's dependency graph.
