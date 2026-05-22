package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeErrorMappingTest {

  @Test
  void permissionDeniedFromAgentAuthorizationReachesClient() throws Exception {
    MemoryTransport[] pair = MemoryTransport.pair();
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "writer",
                "1.0.0",
                (input, ctx) -> {
                  ctx.authorize("fs.write", "/tmp/data");
                  return JobOutcome.Success.inline(input.payload());
                })
            .build();
    runtime.accept(pair[0]);

    try (ArcpClient client = ArcpClient.builder(pair[1]).build()) {
      client.connect(Duration.ofSeconds(5));
      JobHandle handle =
          client.submit(
              ArcpClient.jobSubmit(
                  "writer@1.0.0",
                  JsonNodeFactory.instance.objectNode(),
                  Lease.builder().allow("fs.read", "*").build(),
                  null,
                  null,
                  null));

      try {
        handle.result().get(5, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(PermissionDeniedException.class);
        return;
      }
    } finally {
      runtime.close();
    }

    throw new AssertionError("expected PermissionDeniedException");
  }
}
