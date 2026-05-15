---
title: "Overview"
sdk: java
spec_sections: ["3"]
order: 0
kind: overview
since: "1.0.0"
---

# ARCP Java SDK — Overview

The **Agent Runtime Control Protocol (ARCP)** is a JSON-over-WebSocket
protocol that lets a client submit jobs to a runtime, observe their events,
enforce per-job leases (filesystem, network, tool, budget), and reattach to
work after a disconnect. The full spec lives at
[`../spec/docs/draft-arcp-02.1.md`](../../spec/docs/draft-arcp-02.1.md).

The Java SDK is a JDK 21+ reference implementation. It ships:

- A `Transport` SPI with three implementations: in-memory (pairs for unit
  tests), JDK-builtin `HttpClient.WebSocket` (client), and embedded Jetty 12
  (server).
- A complete client (`ArcpClient`) that handles handshake, idempotency,
  subscribe, heartbeats, ack rate-limiting, and result-chunk reassembly.
- A complete runtime (`ArcpRuntime`) that dispatches jobs onto virtual
  threads, enforces leases and budgets, and serves resume buffers.
- Three middleware adapters for hosts with their own WebSocket plumbing:
  Spring Boot, Jakarta WebSocket, Vert.x.
- An OpenTelemetry transport wrapper that emits a span per envelope.
- A reusable conformance suite (`arcp-tck`) downstream JVM implementations
  can extend.

What's deferred for 1.0.0: HTTP/2 / QUIC transports, mTLS / OAuth2 auth
schemes, stdio newline-delimited JSON transport. See
[CONFORMANCE.md](../CONFORMANCE.md) for the spec-section-keyed status table.

Start with [01-quickstart.md](01-quickstart.md) for a 60-line working
example.
