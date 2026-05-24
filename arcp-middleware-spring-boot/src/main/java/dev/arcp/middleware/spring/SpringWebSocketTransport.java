package dev.arcp.middleware.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link Transport} that bridges a Spring {@link WebSocketSession} to the ARCP wire. Used
 * internally by {@link ArcpWebSocketHandler}; not part of the adapter's public API.
 */
final class SpringWebSocketTransport implements Transport {

  private final WebSocketSession session;
  private final ObjectMapper mapper;
  private final SubmissionPublisher<Envelope> inbound;
  private final ExecutorService inboundExecutor;
  private final ReentrantLock writeLock = new ReentrantLock();

  SpringWebSocketTransport(WebSocketSession session, ObjectMapper mapper) {
    this.session = session;
    this.mapper = mapper != null ? mapper : ArcpMapper.shared();
    this.inboundExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.inbound = new SubmissionPublisher<>(inboundExecutor, 1024);
  }

  void deliver(String frame) {
    try {
      inbound.submit(mapper.readValue(frame, Envelope.class));
    } catch (IOException e) {
      // Malformed frames are dropped; the runtime will see no envelope.
    }
  }

  void completeInbound() {
    inbound.close();
  }

  void failInbound(Throwable t) {
    inbound.closeExceptionally(t);
  }

  @Override
  public void send(Envelope envelope) {
    writeLock.lock();
    try {
      session.sendMessage(new TextMessage(mapper.writeValueAsString(envelope)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public Flow.Publisher<Envelope> incoming() {
    return inbound;
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (IOException ignored) {
      // best-effort close
    }
    inbound.close();
    inboundExecutor.shutdown();
  }
}
