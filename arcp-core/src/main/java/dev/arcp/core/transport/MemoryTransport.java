package dev.arcp.core.transport;

import dev.arcp.core.wire.Envelope;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Paired in-memory transport. {@link #pair()} returns two endpoints whose {@link #send} feeds the
 * other's {@link #incoming}.
 */
public final class MemoryTransport implements Transport {

  /**
   * Typed pair of {@link MemoryTransport} endpoints returned by {@link #pair()}. The {@code
   * runtime} endpoint is intended to be attached to an {@code ArcpRuntime} and the {@code client}
   * endpoint to an {@code ArcpClient}. Either component is functionally interchangeable, but the
   * names exist to remove the index-juggling that array returns required.
   */
  public record Pair(MemoryTransport runtime, MemoryTransport client) {}

  private final SubmissionPublisher<Envelope> inbound;
  private final ExecutorService executor;
  private volatile MemoryTransport peer;
  private volatile boolean closed;

  private MemoryTransport(SubmissionPublisher<Envelope> inbound, ExecutorService executor) {
    this.inbound = inbound;
    this.executor = executor;
  }

  /**
   * Construct a fresh pair of cross-wired {@link MemoryTransport} endpoints. Each {@code send} on
   * either endpoint delivers the envelope to the other's {@link #incoming()} publisher.
   *
   * @return a {@link Pair} of newly constructed endpoints.
   */
  public static Pair pair() {
    // Per-transport publishers using virtual-thread executors keep the
    // dispatch hop off the caller's thread and out of any platform pool.
    ExecutorService aExec = Executors.newVirtualThreadPerTaskExecutor();
    ExecutorService bExec = Executors.newVirtualThreadPerTaskExecutor();
    var aPub = new SubmissionPublisher<Envelope>(aExec, 1024);
    var bPub = new SubmissionPublisher<Envelope>(bExec, 1024);
    MemoryTransport a = new MemoryTransport(aPub, aExec);
    MemoryTransport b = new MemoryTransport(bPub, bExec);
    a.peer = b;
    b.peer = a;
    return new Pair(a, b);
  }

  @Override
  public void send(Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    if (closed) {
      throw new IllegalStateException("transport closed");
    }
    MemoryTransport p = peer;
    if (p == null || p.closed) {
      throw new IllegalStateException("peer transport closed");
    }
    p.inbound.submit(envelope);
  }

  @Override
  public Flow.Publisher<Envelope> incoming() {
    return inbound;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    inbound.close();
    executor.shutdown();
  }
}
