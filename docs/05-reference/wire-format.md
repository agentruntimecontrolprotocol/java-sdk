---
title: "Wire format"
sdk: java
spec_sections: ["5.1", "6", "7", "8"]
order: 3
kind: reference
since: "1.0.0"
---

# Wire format

## Envelope

```json
{
  "arcp": "1",
  "id": "01J…",
  "type": "session.hello",
  "session_id": "sess_…",
  "trace_id": "4bf92f…",
  "job_id": "job_…",
  "event_seq": 1827,
  "payload": { … }
}
```

| Field | Java type | Always required |
|---|---|---|
| `arcp` | `String` | yes (`Envelope.VERSION = "1"`) |
| `id` | [`MessageId`](../../arcp-core/src/main/java/dev/arcp/core/ids/MessageId.java) | yes (ULID, monotonic per process) |
| `type` | discriminator → [`Message.Type`](../../arcp-core/src/main/java/dev/arcp/core/messages/Message.java) | yes |
| `payload` | `JsonNode` then decoded per type | yes |
| `session_id` | [`SessionId`](../../arcp-core/src/main/java/dev/arcp/core/ids/SessionId.java) | absent on `session.hello`, required afterwards |
| `trace_id` | [`TraceId`](../../arcp-core/src/main/java/dev/arcp/core/ids/TraceId.java) | optional, propagated end-to-end |
| `job_id` | [`JobId`](../../arcp-core/src/main/java/dev/arcp/core/ids/JobId.java) | required on job-scoped messages |
| `event_seq` | `Long` | required on `job.event`, monotonic gap-free per session |

Unknown top-level fields are dropped on parse (Jackson
`FAIL_ON_UNKNOWN_PROPERTIES=false` set in
[`ArcpMapper`](../../arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java)).

## Message taxonomy

Seventeen wire types, all members of the sealed
[`Message`](../../arcp-core/src/main/java/dev/arcp/core/messages/Message.java)
interface so dispatch is exhaustive at compile time.

| `type` | Java record |
|---|---|
| `session.hello` | `SessionHello` |
| `session.welcome` | `SessionWelcome` |
| `session.bye` | `SessionBye` |
| `session.ping` | `SessionPing` |
| `session.pong` | `SessionPong` |
| `session.ack` | `SessionAck` |
| `session.list_jobs` | `SessionListJobs` |
| `session.jobs` | `SessionJobs` |
| `job.submit` | `JobSubmit` |
| `job.accepted` | `JobAccepted` |
| `job.event` | `JobEvent` |
| `job.result` | `JobResult` |
| `job.error` | `JobError` |
| `job.cancel` | `JobCancel` |
| `job.subscribe` | `JobSubscribe` |
| `job.subscribed` | `JobSubscribed` |
| `job.unsubscribe` | `JobUnsubscribe` |

## Event-body taxonomy

Ten event kinds, sealed under
[`EventBody`](../../arcp-core/src/main/java/dev/arcp/core/events/EventBody.java).

| `kind` | Java record |
|---|---|
| `log` | `LogEvent` |
| `thought` | `ThoughtEvent` |
| `tool_call` | `ToolCallEvent` |
| `tool_result` | `ToolResultEvent` |
| `status` | `StatusEvent` |
| `metric` | `MetricEvent` |
| `artifact_ref` | `ArtifactRefEvent` |
| `delegate` | `DelegateEvent` |
| `progress` | `ProgressEvent` |
| `result_chunk` | `ResultChunkEvent` |

## Jackson configuration

[`ArcpMapper.create()`](../../arcp-core/src/main/java/dev/arcp/core/wire/ArcpMapper.java)
constructs the canonical mapper:

- `registerModule(new JavaTimeModule())` — `Instant` for §9.5 `expires_at`.
- `enable(USE_BIG_DECIMAL_FOR_FLOATS)` — §9.6 budget arithmetic.
- `disable(FAIL_ON_UNKNOWN_PROPERTIES)` — forward-compatible envelope.
- `disable(WRITE_DATES_AS_TIMESTAMPS)` — ISO-8601 on the wire.
- `setSerializationInclusion(NON_NULL)` — omit null fields globally.

Override on a per-runtime / per-client basis via
`ArcpRuntime.Builder.mapper(ObjectMapper)` and
`ArcpClient.Builder.mapper(ObjectMapper)`.
