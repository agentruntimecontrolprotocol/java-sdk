package dev.arcp.recipes.streamresume;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.SubscribeOptions;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.session.SessionLoop;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recipe: Stream resume across connections.
 *
 * <p>Client 1 submits a streaming job (emits 10 {@link StatusEvent}s) and awaits completion,
 * recording the resume token and last-seen sequence. Its transport then drops unexpectedly (no
 * {@code session.close}), so the runtime parks the session for the resume window (§6.3). Client 2
 * reconnects with the recorded token and re-subscribes with history to replay the event stream,
 * demonstrating fault-tolerant stream consumption. Both connections authenticate with the same
 * bearer token: resume requires a stable principal.
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "streamer",
                "1.0.0",
                (input, ctx) -> {
                  for (int i = 1; i <= 10; i++) {
                    ctx.emit(new StatusEvent("event-" + i, null));
                  }
                  return JobOutcome.Success.inline(input.payload());
                })
            .build();

    // --- Client 1: live consumption. ---
    MemoryTransport.Pair pair1 = MemoryTransport.pair();
    SessionLoop serverSession = runtime.accept(pair1.runtime());

    String resumeToken;
    long lastSeq;
    JobId jobId;
    AtomicInteger firstPassCount = new AtomicInteger();
    CompletableFuture<Void> firstPassDone = new CompletableFuture<>();

    ArcpClient client1 = ArcpClient.builder(pair1.client()).bearer("demo").build();
    try {
      client1.connect(Duration.ofSeconds(5));

      JobHandle handle =
          client1.submit(
              ArcpClient.jobSubmit("streamer@1.0.0", JsonNodeFactory.instance.objectNode()));

      handle
          .events()
          .subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                  s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(EventBody body) {
                  if (body instanceof StatusEvent) {
                    if (firstPassCount.incrementAndGet() == 10) {
                      firstPassDone.complete(null);
                    }
                  }
                }

                @Override
                public void onError(Throwable t) {
                  firstPassDone.completeExceptionally(t);
                }

                @Override
                public void onComplete() {}
              });

      handle.result().get(5, TimeUnit.SECONDS);
      firstPassDone.get(5, TimeUnit.SECONDS);
      jobId = handle.jobId();

      resumeToken = client1.session().resumeToken();
      lastSeq = client1.lastSeenSeq();

      // Simulate an unexpected transport drop: no session.close is sent, so the runtime parks the
      // session for the resume window instead of cancelling it.
      pair1.runtime().close();
    } finally {
      client1.close();
    }

    // The in-memory transport delivers the drop asynchronously; wait until the runtime has parked
    // the session. Over a real network the reconnect delay dwarfs this detection time.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (serverSession.phase() != SessionLoop.Phase.PARKED && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    int firstCount = firstPassCount.get();

    // --- Client 2: resume the parked session and replay via history subscription. ---
    MemoryTransport.Pair pair2 = MemoryTransport.pair();
    runtime.accept(pair2.runtime());

    AtomicInteger replayCount = new AtomicInteger();
    CompletableFuture<Void> replayDone = new CompletableFuture<>();

    try (ArcpClient client2 =
        ArcpClient.builder(pair2.client())
            .bearer("demo")
            .resumeToken(resumeToken)
            .lastEventSeq(lastSeq)
            .build()) {
      client2.connect(Duration.ofSeconds(5));

      Flow.Publisher<EventBody> replayEvents =
          client2.subscribe(jobId, SubscribeOptions.withHistory(0L));
      replayEvents.subscribe(
          new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
              s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EventBody body) {
              if (body instanceof StatusEvent) {
                if (replayCount.incrementAndGet() == 10) {
                  replayDone.complete(null);
                }
              }
            }

            @Override
            public void onError(Throwable t) {
              replayDone.completeExceptionally(t);
            }

            @Override
            public void onComplete() {}
          });

      replayDone.get(5, TimeUnit.SECONDS);
      int replayTotal = replayCount.get();
      assert replayTotal == 10 : "expected 10 replayed events, got " + replayTotal;
      System.out.println(
          "OK stream-resume (first-pass=" + firstCount + ", replay=" + replayTotal + ")");
    }
    runtime.close();
  }
}
