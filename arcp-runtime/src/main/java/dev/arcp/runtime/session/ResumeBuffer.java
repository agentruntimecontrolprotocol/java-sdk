package dev.arcp.runtime.session;

import dev.arcp.core.wire.Envelope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded ring buffer of recent envelopes carrying {@code event_seq}. Supports
 * §6.3 resume by serving envelopes with {@code event_seq > last_event_seq}.
 */
public final class ResumeBuffer {

    private final int capacity;
    private final Deque<Envelope> ring;

    public ResumeBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new ArrayDeque<>(capacity);
    }

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

    public synchronized List<Envelope> since(long lastEventSeq) {
        List<Envelope> out = new ArrayList<>();
        for (Envelope e : ring) {
            Long seq = e.eventSeq();
            if (seq != null && seq > lastEventSeq) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized long earliestSeq() {
        for (Envelope e : ring) {
            if (e.eventSeq() != null) {
                return e.eventSeq();
            }
        }
        return -1;
    }
}
