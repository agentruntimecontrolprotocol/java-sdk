package dev.arcp.examples.delegate;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.events.DelegateEvent;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates manual delegation: the parent agent emits a DelegateEvent; the client detects it and
 * spawns the child job explicitly (the Java runtime does not auto-spawn children).
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "parent",
                "1.0.0",
                (input, ctx) -> {
                  ctx.emit(new DelegateEvent(JobId.generate(), "child@1.0.0"));
                  return JobOutcome.Success.inline(input.payload());
                })
            .agent("child", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
    runtime.accept(pair.runtime());

    try (ArcpClient client = ArcpClient.builder(pair.client()).build()) {
      client.connect(Duration.ofSeconds(5));

      CompletableFuture<JobHandle> childFuture = new CompletableFuture<>();

      JobHandle parentHandle =
          client.submit(
              ArcpClient.jobSubmit("parent@1.0.0", JsonNodeFactory.instance.objectNode()));

      parentHandle
          .events()
          .subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                  s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(EventBody body) {
                  if (body instanceof DelegateEvent) {
                    try {
                      childFuture.complete(
                          client.submit(
                              ArcpClient.jobSubmit(
                                  "child@1.0.0", JsonNodeFactory.instance.objectNode())));
                    } catch (Exception e) {
                      childFuture.completeExceptionally(e);
                    }
                  }
                }

                @Override
                public void onError(Throwable t) {
                  childFuture.completeExceptionally(t);
                }

                @Override
                public void onComplete() {}
              });

      parentHandle.result().get(5, TimeUnit.SECONDS);
      childFuture.get(5, TimeUnit.SECONDS).result().get(5, TimeUnit.SECONDS);
      System.out.println("OK delegate");
    }
    runtime.close();
  }
}
