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

  /**
   * Result of a {@link #claim} that found a prior entry for the same {@code (principal,
   * idempotency_key)} pair (§7.2).
   *
   * @param existing the job id the key already maps to
   */
  public record Conflict(JobId existing) {}

  private record Key(String principal, String idempotencyKey) {}

  private record Entry(JobId jobId, String fingerprint, Instant insertedAt) {}

  private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;
  private final @Nullable ScheduledFuture<?> pruneTask;

  /**
   * Creates a store without background eviction; callers must invoke {@link #prune()} themselves.
   *
   * @param clock the clock used to age entries
   * @param ttl how long a claimed key is retained
   */
  public IdempotencyStore(Clock clock, Duration ttl) {
    this(clock, ttl, null, Duration.ofMinutes(1));
  }

  /**
   * Creates a store that, when a scheduler is supplied, evicts expired entries on a fixed delay.
   *
   * @param clock the clock used to age entries
   * @param ttl how long a claimed key is retained
   * @param scheduler the scheduler running the background prune task, or {@code null} to disable
   *     background eviction
   * @param pruneInterval the delay between background prune runs (clamped to at least 1 ms)
   */
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
   *
   * @param principal the authenticated submitter; keys are scoped per principal (§7.2)
   * @param idempotencyKey the {@code idempotency_key} from {@code job.submit}
   * @param fingerprint the canonical payload fingerprint ({@link IdempotencyFingerprint})
   * @param freshId the job id to claim if the key is unused
   * @return {@code null} if {@code freshId} was claimed, otherwise the conflicting entry
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

  /**
   * Tests whether the stored entry for this key carries an identical payload fingerprint —
   * distinguishing a §7.2 replay (same parameters, reuse the job) from a {@code DUPLICATE_KEY}
   * conflict.
   *
   * @param principal the authenticated submitter
   * @param idempotencyKey the {@code idempotency_key} from {@code job.submit}
   * @param fingerprint the canonical payload fingerprint of the new submission
   * @return {@code true} if an entry exists and its fingerprint matches
   */
  public boolean matchesPayload(Principal principal, String idempotencyKey, String fingerprint) {
    Entry e = entries.get(new Key(principal.id(), idempotencyKey));
    return e != null && e.fingerprint.equals(fingerprint);
  }

  /**
   * Release a previously-claimed key, but only if it still maps to {@code expected}. Used to undo a
   * claim when the corresponding accept fails (§7.2): without this the key stays poisoned for the
   * full TTL and an identical retry is wrongly rejected with {@code DUPLICATE_KEY} (#90).
   *
   * @param principal the authenticated submitter the key is scoped to
   * @param idempotencyKey the {@code idempotency_key} to release
   * @param expected the job id the entry must still map to for the release to apply
   * @return {@code true} if an entry for {@code expected} was removed
   */
  public boolean release(Principal principal, String idempotencyKey, JobId expected) {
    Key key = new Key(principal.id(), idempotencyKey);
    Entry[] removed = new Entry[1];
    entries.computeIfPresent(
        key,
        (k, entry) -> {
          if (entry.jobId.equals(expected)) {
            removed[0] = entry;
            return null;
          }
          return entry;
        });
    return removed[0] != null;
  }

  /**
   * Evict entries older than {@code ttl}. Exposed for deterministic test control; the scheduled
   * background task invokes this method automatically when a scheduler was supplied at construction
   * time.
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
