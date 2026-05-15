package dev.arcp.middleware.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.WebSocketTransport;
import dev.arcp.core.messages.JobResult;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VertxAdapterEndToEndTest {

    @Test
    void adapterAcceptsAndExecutesJob() throws Exception {
        ArcpRuntime runtime = ArcpRuntime.builder()
                .agent("vertx-echo", "1.0.0",
                        (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();

        Vertx vertx = Vertx.vertx();
        try {
            ArcpVertxHandler handler = ArcpVertxHandler.builder()
                    .runtime(runtime)
                    .path("/arcp")
                    .build();

            HttpServer server = vertx.createHttpServer(new HttpServerOptions().setPort(0))
                    .webSocketHandler(handler);
            CompletableFuture<Integer> portFuture = new CompletableFuture<>();
            server.listen().onSuccess(http -> portFuture.complete(http.actualPort()))
                    .onFailure(portFuture::completeExceptionally);
            int port = portFuture.get(5, TimeUnit.SECONDS);

            URI uri = URI.create("ws://127.0.0.1:" + port + "/arcp");
            WebSocketTransport transport = WebSocketTransport.connect(uri);
            try (ArcpClient client = ArcpClient.builder(transport).build()) {
                client.connect(Duration.ofSeconds(5));
                JobHandle handleClient = client.submit(ArcpClient.jobSubmit(
                        "vertx-echo@1.0.0",
                        JsonNodeFactory.instance.objectNode().put("greeting", "vertx")));
                JobResult result = handleClient.result().get(5, TimeUnit.SECONDS);
                assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
                assertThat(result.result().get("greeting").asText()).isEqualTo("vertx");
            }
        } finally {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            vertx.close().onComplete(ar -> closed.complete(null));
            closed.get(5, TimeUnit.SECONDS);
            runtime.close();
        }
    }
}
