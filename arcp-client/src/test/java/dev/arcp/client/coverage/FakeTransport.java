package dev.arcp.client.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic test transport: outbound envelopes are captured in a queue and inbound envelopes
 * are delivered synchronously on the calling thread, so every client dispatch completes before
 * {@link #deliver} returns. This makes branch-precise assertions possible without races.
 */
final class FakeTransport implements Transport {

  static final ObjectMapper MAPPER = ArcpMapper.shared();
  static final SessionId SESSION = SessionId.of("sess_fake");

  final BlockingQueue<Envelope> sent = new LinkedBlockingQueue<>();
  volatile boolean failSends;
  volatile boolean throwOnClose;
  private volatile Flow.@Nullable Subscriber<? super Envelope> subscriber;

  @Override
  public void send(Envelope envelope) {
    if (failSends) {
      throw new IllegalStateException("send failed (test)");
    }
    sent.add(envelope);
  }

  @Override
  public Flow.Publisher<Envelope> incoming() {
    return sub -> {
      subscriber = sub;
      sub.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long n) {}

            @Override
            public void cancel() {}
          });
    };
  }

  @Override
  public void close() {
    if (throwOnClose) {
      throw new IllegalStateException("close failed (test)");
    }
  }

  void deliver(Envelope envelope) {
    requireSubscriber().onNext(envelope);
  }

  void deliver(Message.Type type, Message payload) {
    deliver(envelope(type, payload, null, null, MessageId.generate()));
  }

  void deliver(
      Message.Type type,
      Message payload,
      @Nullable JobId jobId,
      @Nullable Long eventSeq,
      MessageId id) {
    deliver(envelope(type, payload, jobId, eventSeq, id));
  }

  void completeInbound() {
    requireSubscriber().onComplete();
  }

  void errorInbound(Throwable error) {
    requireSubscriber().onError(error);
  }

  Envelope awaitSent(String type) throws InterruptedException {
    Envelope env = sent.poll(3, TimeUnit.SECONDS);
    if (env == null) {
      throw new AssertionError("expected a " + type + " envelope but none was sent");
    }
    if (!env.type().equals(type)) {
      throw new AssertionError("expected " + type + " but client sent " + env.type());
    }
    return env;
  }

  static Envelope envelope(
      Message.Type type,
      Message payload,
      @Nullable JobId jobId,
      @Nullable Long eventSeq,
      MessageId id) {
    return new Envelope(
        Envelope.VERSION,
        id,
        type.wire(),
        SESSION,
        null,
        jobId,
        eventSeq,
        Messages.encodePayload(MAPPER, payload));
  }

  private Flow.Subscriber<? super Envelope> requireSubscriber() {
    Flow.Subscriber<? super Envelope> sub = subscriber;
    if (sub == null) {
      throw new AssertionError("client has not subscribed; call connect() first");
    }
    return sub;
  }
}
