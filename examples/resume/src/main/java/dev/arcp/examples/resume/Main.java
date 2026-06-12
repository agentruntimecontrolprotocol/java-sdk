package dev.arcp.examples.resume;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.session.SessionLoop;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates session resume (§6.3): after an unexpected transport drop the runtime parks the
 * session for the resume window; a second connection presenting the captured {@code resume_token}
 * reattaches to the same logical session and continues where it left off.
 *
 * <p>Resume requires a stable principal across connections, so both clients authenticate with the
 * same bearer token. An explicit {@code session.close} cancels the session instead of parking it,
 * so the first connection is dropped at the transport level rather than closed gracefully.
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();

    // First connection: submit a job and capture resume state.
    MemoryTransport.Pair pair1 = MemoryTransport.pair();
    SessionLoop serverSession = runtime.accept(pair1.runtime());

    ArcpClient client1 = ArcpClient.builder(pair1.client()).bearer("resume-example").build();
    String resumeToken;
    long lastSeq;
    try {
      client1.connect(Duration.ofSeconds(5));
      JobHandle handle =
          client1.submit(
              ArcpClient.jobSubmit(
                  "echo@1.0.0", JsonNodeFactory.instance.objectNode().put("pass", 1)));
      handle.result().get(5, TimeUnit.SECONDS);

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

    // Second connection: present the captured token to reattach to the parked session.
    MemoryTransport.Pair pair2 = MemoryTransport.pair();
    runtime.accept(pair2.runtime());

    try (ArcpClient client2 =
        ArcpClient.builder(pair2.client())
            .bearer("resume-example")
            .resumeToken(resumeToken)
            .lastEventSeq(lastSeq)
            .build()) {
      client2.connect(Duration.ofSeconds(5));
      JobHandle handle =
          client2.submit(
              ArcpClient.jobSubmit(
                  "echo@1.0.0", JsonNodeFactory.instance.objectNode().put("pass", 2)));
      JobResult result = handle.result().get(5, TimeUnit.SECONDS);
      assert result.result().get("pass").asInt() == 2 : "unexpected result: " + result.result();
      System.out.println("OK resume");
    }
    runtime.close();
  }
}
