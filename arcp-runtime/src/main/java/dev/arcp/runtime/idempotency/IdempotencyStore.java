package dev.arcp.runtime.idempotency;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.ids.JobId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * §7.2 idempotency: a {@code (principal, idempotency_key)} pair maps to a
 * stable {@link JobId} within a sliding TTL window. Submitting an identical
 * triple returns the existing job id; a conflicting payload yields
 * {@code DUPLICATE_KEY}.
 */
public final class IdempotencyStore {

    public record Conflict(JobId existing) {}

    private record Key(String principal, String idempotencyKey) {}

    private record Entry(JobId jobId, int payloadHash, Instant insertedAt) {}

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public IdempotencyStore(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /**
     * Look up an existing job id, or claim {@code freshId}. Returns:
     * <ul>
     *   <li>{@code null}: caller proceeds with {@code freshId}.</li>
     *   <li>{@code Conflict(existing)}: the same key already produced a job
     *       (identical payload → reuse; different payload → caller raises
     *       {@code DUPLICATE_KEY}).</li>
     * </ul>
     */
    public Conflict claim(Principal principal, String idempotencyKey, int payloadHash, JobId freshId) {
        prune();
        Key key = new Key(principal.id(), idempotencyKey);
        Entry existing = entries.compute(key, (k, prior) -> {
            if (prior == null) {
                return new Entry(freshId, payloadHash, clock.instant());
            }
            return prior;
        });
        if (existing.jobId.equals(freshId)) {
            return null;
        }
        return new Conflict(existing.jobId);
    }

    public boolean matchesPayload(Principal principal, String idempotencyKey, int payloadHash) {
        Entry e = entries.get(new Key(principal.id(), idempotencyKey));
        return e != null && e.payloadHash == payloadHash;
    }

    private void prune() {
        Instant now = clock.instant();
        entries.values().removeIf(e -> e.insertedAt.plus(ttl).isBefore(now));
    }
}
