# Security Policy

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | Yes                |
| < 1.0   | No                 |

Patch releases for the 1.0 line will continue while the v1 wire format
remains current.

## Reporting a vulnerability

**Do not** open a public GitHub issue for security reports. Email
nficano@gmail.com with the subject `[arcp-java security]` and the body
should include:

- A description of the vulnerability and its impact.
- Reproduction steps or a proof-of-concept (a failing test is ideal).
- The version (or commit SHA) you observed it on.
- Any environmental conditions (JDK version, transport, host adapter).

Acknowledgement target: within 48 hours of receipt. Fix or mitigation
target: within 30 days for code-execution / data-exposure issues;
within 90 days for denial-of-service / behavioral correctness issues.

## Coordinated disclosure

You will receive an estimated patch date and a CVE identifier (if
applicable) before the fix lands. The fix lands in a patch release
tagged on GitHub and published to Maven Central; the security advisory
is published on the repository's Security tab simultaneously.

## Known-sensitive code paths

The following areas are the load-bearing surface for an attacker who
can speak ARCP to the runtime; reviewers and reporters should weight
these heavily:

- [`BearerVerifier`](arcp-core/src/main/java/dev/arcp/core/auth/BearerVerifier.java)
  implementations: a permissive verifier admits anyone to the session.
  The `acceptAny()` default exists for development; production
  deployments wire `staticToken(...)` or a custom verifier.
- [`LeaseGuard.authorize`](arcp-runtime/src/main/java/dev/arcp/runtime/lease/LeaseGuard.java):
  glob-pattern matching for `fs.read`, `fs.write`, `net.fetch`,
  `tool.call`. A bypass here lets an agent escape its lease.
- [`BudgetCounters`](arcp-runtime/src/main/java/dev/arcp/runtime/lease/BudgetCounters.java):
  CAS-on-`BigDecimal`. Arithmetic precision is the integrity guarantee.
- [`SessionLoop.handleSubscribe`](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java)
  and [`handleListJobs`](arcp-runtime/src/main/java/dev/arcp/runtime/session/SessionLoop.java):
  cross-principal scope enforcement.
- Middleware allowedHosts / allowedOrigins on
  [`ArcpJakartaAdapter`](arcp-middleware-jakarta/src/main/java/dev/arcp/middleware/jakarta/ArcpJakartaAdapter.java),
  [`ArcpVertxHandler`](arcp-middleware-vertx/src/main/java/dev/arcp/middleware/vertx/ArcpVertxHandler.java),
  and the Spring Boot autoconfig: DNS-rebind defenses on the upgrade
  request.

## Out of scope

- Bugs in the user-supplied `Agent` implementation.
- Bugs in user-supplied `BearerVerifier` implementations.
- Resource exhaustion from a trusted principal (rate limiting is a
  deployment concern, not an SDK guarantee).
- Vulnerabilities in transitive dependencies that the SDK does not
  surface to its consumers (report those to the upstream project).
