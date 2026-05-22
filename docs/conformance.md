---
title: "Conformance"
sdk: java
spec_sections: []
kind: reference
since: "1.0.0"
---

# Conformance

Full spec §-keyed implementation status with file:line references lives in
[`CONFORMANCE.md`](../CONFORMANCE.md).

## Quick summary

| Spec § | Feature | Status |
|---|---|---|
| §4.1 | WebSocket framing (newline-delimited JSON) | ✅ |
| §4.2 | Stdio framing | ✅ |
| §5.1 | Envelope (`arcp`, `id`, `type`, `payload`, `session_id`, `trace_id`, `job_id`, `event_seq`) | ✅ |
| §6 | Sessions — handshake, feature negotiation | ✅ |
| §6.1 | Bearer auth + `BearerVerifier` SPI | ✅ |
| §6.3 | Resume tokens + `ResumeBuffer` | ✅ |
| §6.4 | Heartbeat (`session.ping` / `session.pong`) | ✅ |
| §6.5 | Ack (`session.ack`) | ✅ |
| §6.6 | List jobs (`session.list_jobs` / `session.jobs`) | ✅ |
| §7 | Jobs — submit, accept, event, result, error | ✅ |
| §7.1 | `job.submit` → `job.accepted` | ✅ |
| §7.3 | Job cancel | ✅ |
| §7.4 | Job timeout | ✅ |
| §7.5 | Agent versioning (`name@version`) | ✅ |
| §7.6 | Job subscribe / unsubscribe | ✅ |
| §8 | Job events (`log`, `thought`, `tool_call`, `tool_result`, `status`, `metric`) | ✅ |
| §8.2.1 | Progress events | ✅ |
| §8.4 | Chunked result (`result_chunk`) | ✅ |
| §8.5 | Vendor extensions (`extensions` payload field) | ✅ |
| §9 | Leases — `fs.read`, `fs.write`, `net.fetch`, `tool.call`, `agent.delegate` | ✅ |
| §9.5 | Lease expiration (`lease_expires_at`) | ✅ |
| §9.6 | Cost budget (`cost.budget`) | ✅ |
| §9.7 | Model-use lease (`model.use`) | ✅ (since 1.1) |
| §9.8 | Provisioned credentials (`CredentialProvisioner` SPI) | ✅ (since 1.1) |
| §10 | Delegation (sub-agent jobs, lease-subset enforcement) | ✅ |
| §11 | Observability (`trace_id` propagation, OTel wrapper) | ✅ |
| §12 | Error codes (all 15) + `ArcpException` hierarchy | ✅ |
| §14 | Credential confidentiality (`Credential.toString()` redaction) | ✅ (since 1.1) |

## Known gaps

| Feature | Status |
|---|---|
| HTTP/2 or QUIC transport | Not planned for 1.x |
| mTLS / OAuth2 client credentials in `BearerVerifier` | Example only; production wiring left to deployers |
| §15.6 trust elevation | Deferred |
| Quarkus / Helidon middleware | Not yet provided |

## TCK

`arcp-tck` is a reusable JUnit 5 conformance suite. Downstream JVM
implementations extend `ArcpConformanceSuite` and supply a `Transport`
factory — the 7 dynamic test templates run against any conformant runtime.

See [`modules/arcp-tck.md`](modules/arcp-tck.md).
