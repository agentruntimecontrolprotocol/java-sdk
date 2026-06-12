package dev.arcp.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.runtime.lease.BudgetCounters;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobListingTest {

  private static final Principal ALICE = new Principal("alice");

  @Test
  void pagesAndProducesContinuationCursor() {
    List<JobRecord> jobs = records(ALICE, 5);
    JobListing.Page page1 = JobListing.page(jobs, ALICE, JobFilter.all(), 2, null);
    assertThat(page1.jobs()).hasSize(2);
    assertThat(page1.nextCursor()).isNotNull();

    JobListing.Page page2 = JobListing.page(jobs, ALICE, JobFilter.all(), 2, page1.nextCursor());
    assertThat(page2.jobs()).hasSize(2);
    JobListing.Page page3 = JobListing.page(jobs, ALICE, JobFilter.all(), 2, page2.nextCursor());
    assertThat(page3.jobs()).hasSize(1);
    assertThat(page3.nextCursor()).isNull();
  }

  @Test
  void filtersByPrincipal() {
    List<JobRecord> jobs = records(ALICE, 2);
    jobs.addAll(records(new Principal("bob"), 3));
    JobListing.Page page = JobListing.page(jobs, ALICE, JobFilter.all(), null, null);
    assertThat(page.jobs()).hasSize(2);
  }

  @Test
  void badCursorThrows() {
    assertThatThrownBy(
            () -> JobListing.page(records(ALICE, 1), ALICE, JobFilter.all(), 10, "#not-base64"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<JobRecord> records(Principal principal, int count) {
    List<JobRecord> out = new ArrayList<>();
    Instant base = Instant.parse("2026-05-21T12:00:00Z");
    for (int i = 0; i < count; i++) {
      out.add(
          new JobRecord(
              JobId.of("job_" + principal.id() + "_" + i),
              "echo@1.0.0",
              principal,
              Lease.empty(),
              LeaseConstraints.none(),
              new BudgetCounters(Map.of()),
              base.plusSeconds(i),
              null));
    }
    return out;
  }
}
