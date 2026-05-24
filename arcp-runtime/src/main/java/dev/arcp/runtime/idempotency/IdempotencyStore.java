package dev.arcp.runtime.idempotency;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.ids.JobId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * §7.2 idempotency: a {@code (principal, idempotency_key)} pair maps to a stable {@link JobId}
 * within a sliding TTL window. Submitting an identical triple returns the existing job id; a
 * conflicting payload yields {@code DUPLICATE_KEY}.
 *
 * <p>The fingerprint passed to {@link #claim(Principal, String, String, JobId)} is a
 * collision-resistant identifier over every semantically relevant {@code JobSubmit} field; see
 * {@code SessionLoop} for the canonical computation.
 *
 * <p>Expired entries are evicted by a background prune task scheduled at construction time (if a
 * scheduler is supplied) rather than synchronously on every {@code claim} call.
 */
public final class IdempotencyStore implements AutoCloseable {

  public record Conflict(JobId existing) {}

  private record Key(String principal, String idempotencyKey) {}

  private record Entry(JobId jobId, String fingerprint, Instant insertedAt) {}

  private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;
  private final @Nullable ScheduledFuture<?> pruneTask;

  public IdempotencyStore(Clock clock, Duration ttl) {
    this(clock, ttl, null, Duration.ofMinutes(1));
  }

  public IdempotencyStore(
      Clock clock,
      Duration ttl,
      @Nullable ScheduledExecutorService scheduler,
      Duration pruneInterval) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    if (scheduler != null) {
      long intervalMillis = Math.max(1L, pruneInterval.toMillis());
      this.pruneTask =
          scheduler.scheduleWithFixedDelay(
              this::pruneQuiet, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    } else {
      this.pruneTask = null;
    }
  }

  /**
   * Look up an existing job id, or claim {@code freshId}. Returns:
   *
   * <ul>
   *   <li>{@code null}: caller proceeds with {@code freshId}.
   *   <li>{@code Conflict(existing)}: the same key already produced a job (identical fingerprint →
   *       reuse; different fingerprint → caller raises {@code DUPLICATE_KEY}).
   * </ul>
   */
  public @Nullable Conflict claim(
      Principal principal, String idempotencyKey, String fingerprint, JobId freshId) {
    Key key = new Key(principal.id(), idempotencyKey);
    Entry existing =
        entries.compute(
            key,
            (k, prior) -> {
              if (prior == null) {
                return new Entry(freshId, fingerprint, clock.instant());
              }
              return prior;
            });
    if (existing.jobId.equals(freshId)) {
      return null;
    }
    return new Conflict(existing.jobId);
  }

  public boolean matchesPayload(Principal principal, String idempotencyKey, String fingerprint) {
    Entry e = entries.get(new Key(principal.id(), idempotencyKey));
    return e != null && e.fingerprint.equals(fingerprint);
  }

  /**
   * Evict entries older than {@code ttl}. Exposed for deterministic test control; the scheduled
   * background task invokes this method automatically when a scheduler was supplied at
   * construction time.
   */
  public void prune() {
    Instant now = clock.instant();
    entries.values().removeIf(e -> e.insertedAt.plus(ttl).isBefore(now));
  }

  int size() {
    return entries.size();
  }

  private void pruneQuiet() {
    try {
      prune();
    } catch (RuntimeException ignored) {
      // best-effort background eviction
    }
  }

  @Override
  public void close() {
    if (pruneTask != null) {
      pruneTask.cancel(false);
    }
  }
}
