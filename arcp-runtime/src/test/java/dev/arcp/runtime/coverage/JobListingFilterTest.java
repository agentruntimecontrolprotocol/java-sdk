package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.runtime.lease.BudgetCounters;
import dev.arcp.runtime.session.JobListing;
import dev.arcp.runtime.session.JobRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Filter combinations, paging boundaries, and cursor variants for JobListing (#33). */
class JobListingFilterTest {

  private static final Principal ALICE = new Principal("alice");
  private static final Instant BASE = Instant.parse("2026-05-21T12:00:00Z");

  private static List<JobRecord> records(int count) {
    List<JobRecord> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(record("job_" + i, "echo@1.0.0", BASE.plusSeconds(i)));
    }
    return out;
  }

  private static JobRecord record(String id, String agent, Instant createdAt) {
    return new JobRecord(
        JobId.of(id),
        agent,
        ALICE,
        Lease.empty(),
        LeaseConstraints.none(),
        new BudgetCounters(Map.of()),
        createdAt,
        null);
  }

  private static String cursor(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @CsvSource({
    "pending, 1", // matches the record status
    "success, 0", // excludes it
  })
  void statusFilterMatchesWireStatus(String status, int expected) {
    JobListing.Page page =
        JobListing.page(records(1), ALICE, new JobFilter(List.of(status), null, null), null, null);
    assertThat(page.jobs()).hasSize(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "'echo@1.0.0', 1", // exact resolved agent
    "echo, 1", // name-only matches any version
    "'echo@2.0.0', 0", // version mismatch
    "other, 0", // name mismatch
  })
  void agentFilterMatchesExactOrName(String agent, int expected) {
    JobListing.Page page =
        JobListing.page(records(1), ALICE, new JobFilter(null, agent, null), null, null);
    assertThat(page.jobs()).hasSize(expected);
  }

  @Test
  void createdAfterIsExclusive() {
    List<JobRecord> jobs = records(3); // BASE, BASE+1, BASE+2
    JobListing.Page page =
        JobListing.page(jobs, ALICE, new JobFilter(null, null, BASE.plusSeconds(1)), null, null);
    assertThat(page.jobs()).hasSize(1);
    assertThat(page.jobs().getFirst().jobId()).isEqualTo(JobId.of("job_2"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -3})
  void nonPositiveLimitReturnsEverything(int limit) {
    JobListing.Page page = JobListing.page(records(4), ALICE, JobFilter.all(), limit, null);
    assertThat(page.jobs()).hasSize(4);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void limitLargerThanMatchesYieldsNoCursor() {
    JobListing.Page page = JobListing.page(records(2), ALICE, JobFilter.all(), 10, null);
    assertThat(page.jobs()).hasSize(2);
    assertThat(page.nextCursor()).isNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void missingOrBlankCursorStartsAtZero(String cursor) {
    JobListing.Page page = JobListing.page(records(2), ALICE, JobFilter.all(), 1, cursor);
    assertThat(page.jobs()).hasSize(1);
    assertThat(page.jobs().getFirst().jobId()).isEqualTo(JobId.of("job_0"));
    assertThat(page.nextCursor()).isNotNull();
  }

  @Test
  void cursorBeyondMatchesReturnsEmptyPage() {
    JobListing.Page page = JobListing.page(records(2), ALICE, JobFilter.all(), 2, cursor("7"));
    assertThat(page.jobs()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void negativeCursorIsClampedToZero() {
    JobListing.Page page = JobListing.page(records(2), ALICE, JobFilter.all(), 1, cursor("-5"));
    assertThat(page.jobs()).hasSize(1);
    assertThat(page.jobs().getFirst().jobId()).isEqualTo(JobId.of("job_0"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"#not-base64", "YWJj" /* "abc": valid base64, not a number */})
  void invalidCursorVariantsThrow(String cursor) {
    assertThatThrownBy(() -> JobListing.page(records(1), ALICE, JobFilter.all(), 1, cursor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid cursor");
  }

  @Test
  void pagingWalksStableOrderToTheEnd() {
    List<JobRecord> jobs = records(5);
    JobListing.Page p1 = JobListing.page(jobs, ALICE, JobFilter.all(), 2, null);
    JobListing.Page p2 = JobListing.page(jobs, ALICE, JobFilter.all(), 2, p1.nextCursor());
    JobListing.Page p3 = JobListing.page(jobs, ALICE, JobFilter.all(), 2, p2.nextCursor());
    assertThat(p1.jobs()).hasSize(2);
    assertThat(p2.jobs()).hasSize(2);
    assertThat(p3.jobs()).hasSize(1);
    assertThat(p3.nextCursor()).isNull();
  }
}
