---
title: "Progress events"
sdk: java
spec_sections: ["8.2", "8.2.1"]
order: 5
kind: feature
since: "1.0.0"
---

# Progress events — §8.2.1

**Feature flag:** `progress`.

A `progress` event carries a current/total tuple plus an optional `units`
label and `message`. The protocol does not act on these; clients render
them.

## Wire shape

```json
{ "kind": "progress", "ts": "2026-05-13T19:42:13Z",
  "body": { "current": 47, "total": 120,
            "units": "files", "message": "Refactoring src/auth/middleware.ts" } }
```

## Java surface

```java
ctx.emit(new ProgressEvent(47, 120L, "files", "Refactoring src/auth"));
```

[`ProgressEvent`](../../arcp-core/src/main/java/dev/arcp/core/events/ProgressEvent.java)
enforces `current ≥ 0` and (when `total` is present) `current ≤ total` in
its compact constructor. Violations throw `IllegalArgumentException` at
construction, so a malformed event never reaches the wire.

## When the feature is not negotiated

Runtimes that do not advertise `progress` should still avoid emitting it;
clients that do not request it should treat the kind as ignorable.
