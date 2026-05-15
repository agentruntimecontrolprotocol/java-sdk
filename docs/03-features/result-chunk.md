---
title: "Streamed results"
sdk: java
spec_sections: ["8.4"]
order: 6
kind: feature
since: "1.0.0"
---

# Streamed results — §8.4

**Feature flag:** `result_chunk`.

For large final results, the agent streams the result as a sequence of
`result_chunk` events terminated by a normal `job.result` carrying the
shared `result_id`. The assembled result is the concatenation of the
chunks' decoded `data` in `chunk_seq` order.

A job that emits any `result_chunk` MUST NOT also return an inline result;
the terminating `job.result` MUST carry `result_id`.

## Wire shape

```json
{ "kind": "result_chunk", "ts": "…",
  "body": { "result_id": "res_…", "chunk_seq": 0,
            "data": "…", "encoding": "utf8", "more": true } }

{ "type": "job.result", "session_id": "…", "job_id": "…", "event_seq": 4827,
  "payload": { "final_status": "success", "result_id": "res_…",
               "result_size": 31457280, "summary": "Generated report." } }
```

## Java surface (agent)

```java
ResultId id = ResultId.generate();
ctx.emit(new ResultChunkEvent(id, 0, "hello ", "utf8", true));
ctx.emit(new ResultChunkEvent(id, 1, "world", "utf8", false));
return JobOutcome.Success.streamed(id, 11, "2 chunks");
```

## Java surface (client)

[`ResultStream`](../../arcp-client/src/main/java/dev/arcp/client/ResultStream.java)
reassembles. It supports in-memory and `OutputStream` sinks:

```java
ResultStream stream = ResultStream.toMemory(resultId);
// (subscribe to handle.events() and feed each ResultChunkEvent to stream.accept)
byte[] assembled = stream.bytes();
```

Out-of-order chunks are buffered until their predecessor arrives;
duplicates throw `DuplicateChunkException`; mid-stream encoding switches
throw `EncodingMismatchException`.

## Example

[`examples/result-chunk/`](../../examples/result-chunk/) wires the agent
and the `ResultStream` end-to-end and asserts the reassembled bytes.
