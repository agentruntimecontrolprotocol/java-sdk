package dev.arcp.runtime.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.lease.BudgetCounters;
import dev.arcp.runtime.session.JobRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CredentialBindingTest {
  @Test
  void attachRecordsIssuedCredentials() {
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    CredentialBinding binding =
        new CredentialBinding(new CountingProvisioner(), store, Clock.systemUTC());
    IssuedCredential issued = issued("cred_1", "secret");
    JobRecord record = record();

    assertThat(binding.attach(record, List.of(issued))).containsExactly(issued.wire());
    assertThat(store.outstanding())
        .containsExactly(
            new CredentialRevocationStore.Outstanding(CredentialId.of("cred_1"), "cred_1"));
  }

  @Test
  void revokeAllRetriesAndMarksRevoked() {
    CountingProvisioner provisioner = new CountingProvisioner();
    provisioner.failuresBeforeSuccess = 2;
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());
    JobRecord record = record();
    binding.attach(record, List.of(issued("cred_1", "secret")));

    binding.revokeAll(record);

    assertThat(provisioner.revokeAttempts.get()).isEqualTo(3);
    assertThat(store.outstanding()).isEmpty();
    assertThat(record.credentials()).isEmpty();
  }

  @Test
  void rotatePublishesStatusAndRevokesPrior() {
    CountingProvisioner provisioner = new CountingProvisioner();
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    List<StatusEvent> events = new ArrayList<>();
    CredentialBinding binding =
        new CredentialBinding(
            provisioner,
            store,
            Clock.systemUTC(),
            (record, body) -> events.add((StatusEvent) body));
    JobRecord record = record();
    binding.attach(record, List.of(issued("cred_1", "old")));

    binding.rotate(record, CredentialId.of("cred_1"), issued("cred_1", "new"));

    assertThat(record.credentials()).extracting(c -> c.wire().value()).containsExactly("new");
    assertThat(events).extracting(StatusEvent::phase).containsExactly("credential_rotated");
    assertThat(provisioner.revoked).containsExactly(CredentialId.of("cred_1"));
  }

  private static JobRecord record() {
    return new JobRecord(
        JobId.of("job_1"),
        "agent@1.0.0",
        new Principal("alice"),
        Lease.empty(),
        LeaseConstraints.none(),
        new BudgetCounters(java.util.Map.of()),
        Instant.parse("2026-05-21T12:00:00Z"),
        null);
  }

  private static IssuedCredential issued(String id, String value) {
    return new IssuedCredential(
        new Credential(
            CredentialId.of(id),
            CredentialScheme.BEARER,
            value,
            "https://llm.example.test/v1",
            null,
            null),
        null);
  }

  private static final class CountingProvisioner implements CredentialProvisioner {
    final AtomicInteger revokeAttempts = new AtomicInteger();
    final List<CredentialId> revoked = new ArrayList<>();
    int failuresBeforeSuccess;

    @Override
    public CompletableFuture<List<IssuedCredential>> issue(
        Lease lease, LeaseConstraints constraints, dev.arcp.runtime.agent.JobContext ctx) {
      return CompletableFuture.completedFuture(List.of(issued("cred_1", "secret")));
    }

    @Override
    public CompletableFuture<Void> revoke(CredentialId id) {
      int attempt = revokeAttempts.incrementAndGet();
      if (attempt <= failuresBeforeSuccess) {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("transient"));
        return failed;
      }
      revoked.add(id);
      return CompletableFuture.completedFuture(null);
    }
  }
}
