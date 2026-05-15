package dev.arcp.runtime.heartbeat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks last inbound activity per session. The dead-link decision is "two
 * consecutive missed intervals" — at every scheduler tick the runtime asks
 * {@link #shouldClose(Duration)} and acts on a {@code true} reply.
 */
public final class HeartbeatTracker {

    private final Clock clock;
    private final AtomicReference<Instant> lastInbound;

    public HeartbeatTracker(Clock clock) {
        this.clock = clock;
        this.lastInbound = new AtomicReference<>(clock.instant());
    }

    public void onInbound() {
        lastInbound.set(clock.instant());
    }

    public Duration sinceLastInbound() {
        return Duration.between(lastInbound.get(), clock.instant());
    }

    /** Per §6.4: two consecutive missed intervals MAY close the transport. */
    public boolean shouldClose(Duration interval) {
        return sinceLastInbound().compareTo(interval.multipliedBy(2)) > 0;
    }

    /** Per §6.4: one elapsed interval triggers an outbound ping. */
    public boolean shouldPing(Duration interval) {
        return sinceLastInbound().compareTo(interval) >= 0;
    }
}
