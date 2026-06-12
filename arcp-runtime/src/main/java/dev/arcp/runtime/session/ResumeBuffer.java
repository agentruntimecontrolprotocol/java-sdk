package dev.arcp.runtime.session;

import dev.arcp.core.wire.Envelope;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded ring buffer of recent envelopes carrying {@code event_seq}. Supports §6.3 resume by
 * serving envelopes with {@code event_seq > last_event_seq}.
 */
public final class ResumeBuffer {

  private final int capacity;
  private final Deque<Envelope> ring;

  /**
   * Creates a buffer retaining at most {@code capacity} envelopes.
   *
   * @param capacity the maximum number of envelopes retained for replay
   * @throws IllegalArgumentException if {@code capacity} is not positive
   */
  public ResumeBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.capacity = capacity;
    this.ring = new ArrayDeque<>(capacity);
  }

  /**
   * Records an outbound envelope, evicting the oldest entry when full. Envelopes without an {@code
   * event_seq} (session control messages, §6.4) are not buffered.
   *
   * @param envelope the outbound envelope to retain for replay
   */
  public synchronized void record(Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    if (envelope.eventSeq() == null) {
      return;
    }
    if (ring.size() == capacity) {
      ring.removeFirst();
    }
    ring.addLast(envelope);
  }

  /**
   * Returns the buffered envelopes to replay after a resume (§6.3).
   *
   * @param lastEventSeq the {@code last_event_seq} the client reported in {@code session.resume}
   * @return buffered envelopes with {@code event_seq} greater than {@code lastEventSeq}, in order
   */
  public synchronized List<Envelope> since(long lastEventSeq) {
    return ring.stream().filter(e -> e.eventSeq() != null && e.eventSeq() > lastEventSeq).toList();
  }

  /**
   * Returns the oldest buffered sequence number, used to detect when a requested replay falls
   * outside the buffer and must fail with {@code RESUME_WINDOW_EXPIRED} (§6.3).
   *
   * @return the earliest buffered {@code event_seq}, or {@code -1} when the buffer is empty
   */
  public synchronized long earliestSeq() {
    return ring.stream().map(Envelope::eventSeq).filter(Objects::nonNull).findFirst().orElse(-1L);
  }
}
