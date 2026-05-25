package dev.arcp.examples.ackbackpressure;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates manual ack/back-pressure: client opts out of auto-ack, counts events itself, then
 * sends a single explicit ack at the end.
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "ticker",
                "1.0.0",
                (input, ctx) -> {
                  for (int i = 1; i <= 20; i++) {
                    ctx.emit(new StatusEvent("tick-" + i, null));
                  }
                  return JobOutcome.Success.inline(input.payload());
                })
            .build();
    runtime.accept(pair.runtime());

    AtomicInteger received = new AtomicInteger();

    try (ArcpClient client =
        ArcpClient.builder(pair.client())
            .autoAck(false)
            .features(EnumSet.allOf(Feature.class))
            .build()) {
      client.connect(Duration.ofSeconds(5));

      JobHandle handle =
          client.submit(
              ArcpClient.jobSubmit("ticker@1.0.0", JsonNodeFactory.instance.objectNode()));

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
                  received.incrementAndGet();
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
              });

      handle.result().get(5, TimeUnit.SECONDS);

      // Allow event delivery to finish.
      Thread.sleep(200);

      assert received.get() == 20 : "expected 20 events, got " + received.get();

      // Explicit ack after processing all events.
      client.ack(client.lastSeenSeq());
      System.out.println("OK ack-backpressure");
    }
    runtime.close();
  }
}
