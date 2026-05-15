package dev.arcp.middleware.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.WebSocketTransport;
import dev.arcp.core.messages.JobResult;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

@SpringBootTest(
        classes = SpringBootEndToEndTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringBootEndToEndTest {

    @LocalServerPort
    int port;

    @Autowired
    ArcpRuntime runtime;

    @SpringBootApplication
    static class TestApp {
        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        @Bean
        ArcpRuntime arcpRuntime() {
            return ArcpRuntime.builder()
                    .agent("spring-echo", "1.0.0",
                            (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                    .build();
        }
    }

    @Test
    void springAdapterAcceptsAndExecutesJob() throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + port + "/arcp");
        WebSocketTransport transport = WebSocketTransport.connect(uri);
        try (ArcpClient client = ArcpClient.builder(transport).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "spring-echo@1.0.0",
                    JsonNodeFactory.instance.objectNode().put("greeting", "spring")));
            JobResult result = handle.result().get(5, TimeUnit.SECONDS);
            assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
            assertThat(result.result().get("greeting").asText()).isEqualTo("spring");
        }
        runtime.close();
    }
}
