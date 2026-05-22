package dev.arcp.examples.vendorextensions;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.ThoughtEvent;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates vendor-extension events: the agent emits a {@link ThoughtEvent} prefixed with a
 * vendor namespace; the client receives and inspects the payload.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        MemoryTransport[] pair = MemoryTransport.pair();

        ArcpRuntime runtime =
                ArcpRuntime.builder()
                        .agent(
                                "thinker",
                                "1.0.0",
                                (input, ctx) -> {
                                    ctx.emit(new ThoughtEvent("x-vendor.demo: custom payload here"));
                                    return JobOutcome.Success.inline(input.payload());
                                })
                        .build();
        runtime.accept(pair[0]);

        CompletableFuture<ThoughtEvent> thoughtReceived = new CompletableFuture<>();

        try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));

            JobHandle handle =
                    client.submit(
                            ArcpClient.jobSubmit(
                                    "thinker@1.0.0", JsonNodeFactory.instance.objectNode()));

            handle.events()
                    .subscribe(
                            new Flow.Subscriber<>() {
                                @Override
                                public void onSubscribe(Flow.Subscription s) {
                                    s.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(EventBody body) {
                                    if (body instanceof ThoughtEvent te) {
                                        thoughtReceived.complete(te);
                                    }
                                }

                                @Override
                                public void onError(Throwable t) {
                                    thoughtReceived.completeExceptionally(t);
                                }

                                @Override
                                public void onComplete() {}
                            });

            handle.result().get(5, TimeUnit.SECONDS);
            ThoughtEvent te = thoughtReceived.get(5, TimeUnit.SECONDS);
            assert te.text().startsWith("x-vendor.demo:")
                    : "unexpected vendor event text: " + te.text();
            System.out.println("OK vendor-extensions");
        }
        runtime.close();
    }
}
