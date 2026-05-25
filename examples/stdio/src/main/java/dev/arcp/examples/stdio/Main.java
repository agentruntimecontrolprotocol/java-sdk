package dev.arcp.examples.stdio;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.StdioTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the StdioTransport: runtime and client communicate over cross-wired
 * PipedInputStream/PipedOutputStream pairs, mimicking a subprocess stdio channel.
 */
public final class Main {
  private static final int EOF = -1;

  public static void main(String[] args) throws Exception {
    PipePair pipe = pipePair();

    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "stdio-echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
    runtime.accept(new StdioTransport(pipe.runtimeIn, pipe.runtimeOut).start());

    try (ArcpClient client =
        ArcpClient.builder(new StdioTransport(pipe.clientIn, pipe.clientOut).start()).build()) {
      client.connect(Duration.ofSeconds(5));
      JobHandle handle =
          client.submit(
              ArcpClient.jobSubmit(
                  "stdio-echo@1.0.0", JsonNodeFactory.instance.objectNode().put("via", "stdio")));
      JobResult result = handle.result().get(5, TimeUnit.SECONDS);
      assert "stdio".equals(result.result().get("via").asText())
          : "unexpected result: " + result.result();
      System.out.println("OK stdio");
    }
    runtime.close();
  }

  private static PipePair pipePair() {
    BlockingQueue<Integer> runtimeToClient = new LinkedBlockingQueue<>();
    BlockingQueue<Integer> clientToRuntime = new LinkedBlockingQueue<>();
    return new PipePair(
        stream(clientToRuntime),
        sink(runtimeToClient),
        stream(runtimeToClient),
        sink(clientToRuntime));
  }

  private record PipePair(
      InputStream runtimeIn,
      OutputStream runtimeOut,
      InputStream clientIn,
      OutputStream clientOut) {}

  private static OutputStream sink(BlockingQueue<Integer> queue) {
    return new OutputStream() {
      private volatile boolean closed;

      @Override
      public void write(int b) throws IOException {
        if (closed) {
          throw new IOException("stream closed");
        }
        queue.add(b & 0xFF);
      }

      @Override
      public void close() {
        if (!closed) {
          closed = true;
          queue.add(EOF);
        }
      }
    };
  }

  private static InputStream stream(BlockingQueue<Integer> queue) {
    return new InputStream() {
      private volatile boolean closed;

      @Override
      public int read() throws IOException {
        if (closed) {
          return EOF;
        }
        try {
          Integer value = queue.poll(30, TimeUnit.SECONDS);
          if (value == null || value == EOF) {
            closed = true;
            return EOF;
          }
          return value;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) {
          return 0;
        }
        int first = read();
        if (first == EOF) {
          return EOF;
        }
        bytes[offset] = (byte) first;
        int count = 1;
        while (count < length) {
          Integer next = queue.poll();
          if (next == null) {
            break;
          }
          if (next == EOF) {
            closed = true;
            queue.add(EOF);
            break;
          }
          bytes[offset + count] = next.byteValue();
          count++;
        }
        return count;
      }

      @Override
      public void close() {
        closed = true;
      }
    };
  }
}
