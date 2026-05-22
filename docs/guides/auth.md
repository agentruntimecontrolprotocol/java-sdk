---
title: "Authentication"
sdk: java
spec_sections: ["6.1"]
kind: guide
since: "1.0.0"
---

# Authentication (§6.1)

ARCP uses bearer tokens in `session.hello`. The runtime verifies tokens via
the `BearerVerifier` SPI.

## Client — sending a token

```java
ArcpClient client = ArcpClient.builder(transport)
    .bearer("my-token")
    .build();
client.connect(Duration.ofSeconds(5));
```

## Runtime — static token (development)

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .verifier(BearerVerifier.staticToken("hunter2", new Principal("alice")))
    .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
    .build();
```

`BearerVerifier.staticToken(token, principal)` accepts exactly that one
token and binds the given `Principal`. Jobs submitted in this session run
under `alice`.

The default (no verifier configured) accepts any non-empty token and
creates an anonymous principal. **Do not use this in production.**

## Runtime — custom verifier SPI

Implement `BearerVerifier` to integrate with your auth system:

```java
public class JwtVerifier implements BearerVerifier {
    @Override
    public Principal verify(String token) throws UnauthenticatedException {
        // validate JWT, extract sub claim
        Claims claims = Jwts.parser().verifyWith(publicKey).parseSignedClaims(token);
        return new Principal(claims.getSubject());
    }
}

ArcpRuntime runtime = ArcpRuntime.builder()
    .verifier(new JwtVerifier())
    .build();
```

Throw `UnauthenticatedException` (or any unchecked exception — the runtime
wraps it) to reject a token.

## HMAC example

A complete HMAC-SHA256 verifier lives in
[`examples/custom-auth/`](../../examples/custom-auth/). It demonstrates:

- Signing a token with `Mac.getInstance("HmacSHA256")`
- Timing-safe comparison with `MessageDigest.isEqual`
- Returning a `Principal` with metadata from the token claims

## Principal scope

Jobs are scoped to the `Principal` returned by the verifier. `listJobs`
and `subscribe` are restricted to jobs owned by the calling principal — no
cross-principal data leaks.

## Provisioned credentials (§9.8)

For per-job short-lived credentials (API keys, DB passwords), see
[guides/credentials.md](credentials.md).
