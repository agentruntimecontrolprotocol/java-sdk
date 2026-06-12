package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.runtime.credentials.IssuedCredential;
import dev.arcp.runtime.lease.BudgetCounters;
import dev.arcp.runtime.session.JobRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Status transition matrix, history capacity, and credential bookkeeping for JobRecord (#33). */
class JobRecordTransitionTest {

  private static JobRecord record(int historyCapacity) {
    return new JobRecord(
        JobId.of("job_1"),
        "echo@1.0.0",
        new Principal("alice"),
        Lease.empty(),
        LeaseConstraints.none(),
        new BudgetCounters(Map.of()),
        Instant.parse("2026-05-21T12:00:00Z"),
        null,
        historyCapacity);
  }

  @ParameterizedTest
  @EnumSource(
      value = JobRecord.Status.class,
      names = {"SUCCESS", "ERROR", "CANCELLED", "TIMED_OUT"})
  void terminalStatesRejectFurtherTransitions(JobRecord.Status terminal) {
    JobRecord rec = record(8);
    assertThat(rec.transitionTo(terminal)).isTrue();
    for (JobRecord.Status next : JobRecord.Status.values()) {
      assertThat(rec.transitionTo(next)).as("from %s to %s", terminal, next).isFalse();
    }
    assertThat(rec.status()).isEqualTo(terminal);
  }

  @Test
  void pendingAndRunningAllowTransitions() {
    JobRecord rec = record(8);
    assertThat(rec.status()).isEqualTo(JobRecord.Status.PENDING);
    assertThat(rec.transitionTo(JobRecord.Status.RUNNING)).isTrue();
    assertThat(rec.transitionTo(JobRecord.Status.SUCCESS)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "PENDING, pending, false",
    "RUNNING, running, false",
    "SUCCESS, success, true",
    "ERROR, error, true",
    "CANCELLED, cancelled, true",
    "TIMED_OUT, timed_out, true",
  })
  void wireAndTerminalMatrix(JobRecord.Status status, String wire, boolean terminal) {
    assertThat(status.wire()).isEqualTo(wire);
    assertThat(status.terminal()).isEqualTo(terminal);
  }

  @Test
  void historyEvictsOldestAtCapacity() {
    JobRecord rec = record(2);
    rec.recordEvent(1, event("e1"));
    rec.recordEvent(2, event("e2"));
    rec.recordEvent(3, event("e3"));
    assertThat(rec.eventHistorySize()).isEqualTo(2);
    assertThat(rec.eventsSince(0))
        .extracting(JobRecord.RecordedEvent::producerSeq)
        .containsExactly(2L, 3L);
    assertThat(rec.eventsSince(2))
        .extracting(JobRecord.RecordedEvent::producerSeq)
        .containsExactly(3L);
    assertThat(rec.eventsSince(99)).isEmpty();
  }

  private static JobEvent event(String message) {
    return new JobEvent(
        "log", Instant.EPOCH, JsonNodeFactory.instance.objectNode().put("message", message));
  }

  @Test
  void nonPositiveHistoryCapacityFallsBackToDefault() {
    JobRecord rec = record(0);
    for (int i = 1; i <= JobRecord.DEFAULT_HISTORY_CAPACITY + 1; i++) {
      rec.recordEvent(i, event("e" + i));
    }
    assertThat(rec.eventHistorySize()).isEqualTo(JobRecord.DEFAULT_HISTORY_CAPACITY);
  }

  @Test
  void subscribersAddAndRemoveWhere() {
    JobRecord rec = record(8);
    JobRecord.Subscriber sub = new JobRecord.Subscriber(null, JobId.of("job_1"));
    rec.addSubscriber(sub);
    assertThat(rec.subscribers()).hasSize(1);
    assertThat(rec.removeSubscribersWhere(s -> s.jobId().equals(JobId.of("job_other")))).isFalse();
    assertThat(rec.removeSubscribersWhere(s -> s == sub)).isTrue();
    assertThat(rec.subscribers()).isEmpty();
  }

  @Test
  void replaceCredentialSwapsMatchingAndAppendsUnknown() {
    JobRecord rec = record(8);
    rec.setCredentials(List.of(issued("cred_1", "a"), issued("cred_2", "b")));

    // Replacing the second entry walks past the non-matching first entry.
    IssuedCredential prior =
        rec.replaceCredential(CredentialId.of("cred_2"), issued("cred_2", "b2"));
    assertThat(prior).isNotNull();
    assertThat(prior.wire().value()).isEqualTo("b");

    // Replacing an unknown id appends and reports no prior credential.
    assertThat(rec.replaceCredential(CredentialId.of("cred_3"), issued("cred_3", "c"))).isNull();
    assertThat(rec.credentials()).hasSize(3);

    assertThat(rec.drainCredentials()).hasSize(3);
    assertThat(rec.credentials()).isEmpty();
  }

  private static IssuedCredential issued(String id, String value) {
    return new IssuedCredential(
        new Credential(
            CredentialId.of(id), CredentialScheme.BEARER, value, "https://x.example", null, null),
        null);
  }
}
