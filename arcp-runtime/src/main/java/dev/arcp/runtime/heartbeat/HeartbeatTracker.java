package dev.arcp.runtime.heartbeat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks last inbound activity per session. The dead-link decision is "two consecutive missed
 * intervals" — at every scheduler tick the runtime asks {@link #shouldClose(Duration)} and acts on
 * a {@code true} reply.
 */
public final class HeartbeatTracker {

  private final Clock clock;
  private final AtomicReference<Instant> lastInbound;

  /**
   * Creates a tracker that counts construction time as the first inbound activity.
   *
   * @param clock the clock used to timestamp inbound messages
   */
  public HeartbeatTracker(Clock clock) {
    this.clock = clock;
    this.lastInbound = new AtomicReference<>(clock.instant());
  }

  /** Records inbound activity now; any message counts, not just {@code session.ping} (§6.4). */
  public void onInbound() {
    lastInbound.set(clock.instant());
  }

  /**
   * Returns the time elapsed since the most recent inbound message.
   *
   * @return the duration since the last inbound activity
   */
  public Duration sinceLastInbound() {
    return Duration.between(lastInbound.get(), clock.instant());
  }

  /**
   * Per §6.4: two consecutive missed intervals MAY close the transport.
   *
   * @param interval the negotiated {@code heartbeat_interval_sec} as a duration
   * @return {@code true} if more than two intervals have elapsed without inbound activity
   */
  public boolean shouldClose(Duration interval) {
    return sinceLastInbound().compareTo(interval.multipliedBy(2)) > 0;
  }

  /**
   * Per §6.4: one elapsed interval triggers an outbound ping.
   *
   * @param interval the negotiated {@code heartbeat_interval_sec} as a duration
   * @return {@code true} if at least one interval has elapsed without inbound activity
   */
  public boolean shouldPing(Duration interval) {
    return sinceLastInbound().compareTo(interval) >= 0;
  }
}
