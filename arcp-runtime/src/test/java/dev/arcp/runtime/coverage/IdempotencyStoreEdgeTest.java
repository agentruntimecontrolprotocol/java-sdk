package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.ids.JobId;
import dev.arcp.runtime.idempotency.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** TTL expiry, claim/release/matchesPayload branches for IdempotencyStore (#33). */
class IdempotencyStoreEdgeTest {

  private static final Principal ALICE = new Principal("alice");

  @Test
  void claimReleaseAndMatchBranches() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    try (IdempotencyStore store = new IdempotencyStore(clock, Duration.ofHours(1))) {
      JobId first = JobId.of("job_1");
      assertThat(store.claim(ALICE, "k", "fp", first)).isNull();

      // Same key: conflict carries the existing job id regardless of fingerprint.
      IdempotencyStore.Conflict conflict = store.claim(ALICE, "k", "fp", JobId.of("job_2"));
      assertThat(conflict).isNotNull();
      assertThat(conflict.existing()).isEqualTo(first);

      assertThat(store.matchesPayload(ALICE, "k", "fp")).isTrue();
      assertThat(store.matchesPayload(ALICE, "k", "other-fp")).isFalse();
      assertThat(store.matchesPayload(ALICE, "missing", "fp")).isFalse();

      // Release only removes when the expected job id still owns the claim.
      assertThat(store.release(ALICE, "k", JobId.of("job_wrong"))).isFalse();
      assertThat(store.release(ALICE, "missing", first)).isFalse();
      assertThat(store.release(ALICE, "k", first)).isTrue();
      assertThat(store.claim(ALICE, "k", "fp", JobId.of("job_3"))).isNull();
    }
  }

  @Test
  void pruneEvictsOnlyExpiredEntries() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    try (IdempotencyStore store = new IdempotencyStore(clock, Duration.ofMinutes(10))) {
      store.claim(ALICE, "old", "fp", JobId.of("job_old"));
      clock.advance(Duration.ofMinutes(9));
      store.claim(ALICE, "young", "fp", JobId.of("job_young"));

      clock.advance(Duration.ofMinutes(2)); // old: 11m (expired), young: 2m (kept)
      store.prune();

      assertThat(store.claim(ALICE, "old", "fp", JobId.of("job_new"))).isNull();
      IdempotencyStore.Conflict young = store.claim(ALICE, "young", "fp", JobId.of("job_x"));
      assertThat(young).isNotNull();
      assertThat(young.existing()).isEqualTo(JobId.of("job_young"));
    }
  }

  @Test
  void scheduledPruneTaskRunsAndSurvivesClockFailures() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ManualScheduler scheduler = new ManualScheduler();
    try (IdempotencyStore store =
        new IdempotencyStore(clock, Duration.ofMinutes(10), scheduler, Duration.ofMinutes(1))) {
      ManualScheduler.Task prune = scheduler.awaitTask(0);
      assertThat(prune.periodic()).isTrue();

      store.claim(ALICE, "k", "fp", JobId.of("job_1"));
      clock.advance(Duration.ofMinutes(11));
      prune.run();
      assertThat(store.claim(ALICE, "k", "fp", JobId.of("job_2"))).isNull();

      // A clock failure inside the background prune is swallowed.
      clock.throwOnRead(true);
      prune.run();
      clock.throwOnRead(false);

      store.close();
      assertThat(prune.isCancelled()).isTrue();
    }
  }
}
