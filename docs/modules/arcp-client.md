---
title: "arcp-client"
sdk: java
kind: module
since: "1.0.0"
---

# `arcp-client`

Client-side SDK. Submit jobs, observe events, await results.

## Dependency

```kotlin
implementation("dev.arcp:arcp-client:1.0.0")
```

## Key classes

### `ArcpClient`

Main entry point. Implements `AutoCloseable`.

```java
ArcpClient client = ArcpClient.builder(transport)
    .bearer("my-token")
    .features("heartbeat", "ack", "progress")
    .mapper(customMapper)          // optional — override ArcpMapper default
    .ackInterval(Duration.ofMillis(200))
    .resumeToken(token)            // optional — for reconnect
    .lastEventSeq(seq)             // optional — for reconnect
    .build();

client.connect(Duration.ofSeconds(5));   // performs session.hello / welcome

JobHandle handle = client.submit(...);
Page<JobSummary> page = client.listJobs(JobFilter.all());
JobHandle observer = client.subscribe(jobId, SubscribeOptions.live());

client.close(); // sends session.bye, closes transport
```

### `JobHandle`

Returned by `client.submit(...)` and `client.subscribe(...)`.

```java
JobId                         handle.jobId();
AgentRef                      handle.agentRef();       // resolved name@version
Flow.Publisher<EventBody>     handle.events();         // replaying publisher
CompletableFuture<JobResult>  handle.result();
ResultStream                  handle.resultStream(id); // chunked result
void                          handle.cancel();
long                          handle.lastReceivedSeq();
```

`handle.events()` is backed by a buffered, replaying
`SubmissionPublisher`. Late subscribers receive full history.

### `ResultStream`

For chunked results (§8.4):

```java
ResultStream stream = handle.resultStream(resultId);
stream.chunks().forEach(chunk -> System.out.println(chunk.data()));
// or:
String all = stream.toMemory(resultId);
```

### `WebSocketTransport`

JDK `HttpClient.WebSocket` adapter:

```java
WebSocketTransport t = WebSocketTransport.connect(URI.create("ws://.../arcp"));
// or with builder for TLS / custom headers:
WebSocketTransport t = WebSocketTransport.builder(uri)
    .httpClient(customClient)
    .header("X-Custom", "value")
    .build();
```

## Packages

| Package | Contents |
|---|---|
| `dev.arcp.client` | `ArcpClient`, `JobHandle`, `ResultStream`, `JobFilter`, `SubscribeOptions` |
| `dev.arcp.client.transport` | `WebSocketTransport` |
