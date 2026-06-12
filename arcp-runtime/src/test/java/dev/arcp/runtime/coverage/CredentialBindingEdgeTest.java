package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.agent.JobContext;
import dev.arcp.runtime.credentials.CredentialBinding;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.CredentialRevocationStore;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.IssuedCredential;
import dev.arcp.runtime.lease.BudgetCounters;
import dev.arcp.runtime.session.JobRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Rotation with surplus, revoke failure paths, and provider-handle fallbacks (#33, #98). */
class CredentialBindingEdgeTest {

  private static JobRecord record() {
    return new JobRecord(
        JobId.of("job_1"),
        "agent@1.0.0",
        new Principal("alice"),
        Lease.empty(),
        LeaseConstraints.none(),
        new BudgetCounters(Map.of()),
        Instant.parse("2026-05-21T12:00:00Z"),
        null);
  }

  private static IssuedCredential issued(String id, String value, String handle) {
    return new IssuedCredential(
        new Credential(
            CredentialId.of(id),
            CredentialScheme.BEARER,
            value,
            "https://llm.example/v1",
            null,
            null),
        handle);
  }

  private static final class FlakyProvisioner implements CredentialProvisioner {
    final AtomicInteger revokeAttempts = new AtomicInteger();
    final List<CredentialId> revoked = new CopyOnWriteArrayList<>();
    int failuresBeforeSuccess;
    boolean throwRaw;
    boolean throwCompletionWithoutCause;

    @Override
    public CompletableFuture<List<IssuedCredential>> issue(
        Lease lease, LeaseConstraints constraints, JobContext ctx) {
      return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Void> revoke(CredentialId id) {
      int attempt = revokeAttempts.incrementAndGet();
      if (attempt <= failuresBeforeSuccess) {
        if (throwCompletionWithoutCause) {
          throw new CompletionException((Throwable) null);
        }
        if (throwRaw) {
          throw new IllegalStateException("revoker down");
        }
        return CompletableFuture.failedFuture(new IllegalStateException("transient"));
      }
      revoked.add(id);
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class RecordingStore implements CredentialRevocationStore {
    private final InMemoryCredentialRevocationStore delegate =
        new InMemoryCredentialRevocationStore();
    final List<CredentialId> failed = new CopyOnWriteArrayList<>();

    @Override
    public void record(CredentialId id, String providerHandle) {
      delegate.record(id, providerHandle);
    }

    @Override
    public void markRevoked(CredentialId id) {
      delegate.markRevoked(id);
    }

    @Override
    public List<Outstanding> outstanding() {
      return delegate.outstanding();
    }

    @Override
    public void markRevocationFailed(CredentialId id, Throwable cause) {
      failed.add(id);
    }
  }

  @Test
  void attachUsesProviderHandleWhenPresent() {
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    CredentialBinding binding =
        new CredentialBinding(new FlakyProvisioner(), store, Clock.systemUTC());
    binding.attach(record(), List.of(issued("cred_1", "v", "provider-handle-1")));
    assertThat(store.outstanding())
        .containsExactly(
            new CredentialRevocationStore.Outstanding(
                CredentialId.of("cred_1"), "provider-handle-1"));
  }

  @Test
  void rotateWithoutPriorCredentialRecordsTheNewOne() {
    FlakyProvisioner provisioner = new FlakyProvisioner();
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());
    JobRecord rec = record();

    binding.rotate(rec, CredentialId.of("cred_new"), issued("cred_new", "v", "h-new"));

    // No prior credential existed, so nothing was revoked; the new one is tracked.
    assertThat(provisioner.revoked).isEmpty();
    assertThat(rec.credentials()).hasSize(1);
    assertThat(store.outstanding())
        .containsExactly(
            new CredentialRevocationStore.Outstanding(CredentialId.of("cred_new"), "h-new"));
  }

  @Test
  void revokeFailingAllAttemptsMarksRevocationFailed() {
    FlakyProvisioner provisioner = new FlakyProvisioner();
    provisioner.failuresBeforeSuccess = 99;
    RecordingStore store = new RecordingStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());
    JobRecord rec = record();
    binding.attach(rec, List.of(issued("cred_1", "v", null)));

    binding.revokeAll(rec);

    assertThat(provisioner.revokeAttempts.get()).isEqualTo(3);
    assertThat(store.failed).containsExactly(CredentialId.of("cred_1"));
  }

  @Test
  void rawRuntimeExceptionRetriesThenSucceeds() {
    FlakyProvisioner provisioner = new FlakyProvisioner();
    provisioner.failuresBeforeSuccess = 1;
    provisioner.throwRaw = true;
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());
    JobRecord rec = record();
    binding.attach(rec, List.of(issued("cred_1", "v", null)));

    binding.revokeAll(rec);

    assertThat(provisioner.revoked).containsExactly(CredentialId.of("cred_1"));
    assertThat(store.outstanding()).isEmpty();
  }

  @Test
  void rawRuntimeExceptionExhaustingRetriesMarksFailure() {
    FlakyProvisioner provisioner = new FlakyProvisioner();
    provisioner.failuresBeforeSuccess = 99;
    provisioner.throwRaw = true;
    RecordingStore store = new RecordingStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());

    binding.revokeMinted(issued("cred_m", "v", "h-m"));

    assertThat(provisioner.revokeAttempts.get()).isEqualTo(3);
    assertThat(store.failed).containsExactly(CredentialId.of("cred_m"));
  }

  @Test
  void completionExceptionWithoutCauseIsHandled() {
    FlakyProvisioner provisioner = new FlakyProvisioner();
    provisioner.failuresBeforeSuccess = 99;
    provisioner.throwCompletionWithoutCause = true;
    RecordingStore store = new RecordingStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());

    binding.revokeMinted(issued("cred_c", "v", null));

    assertThat(store.failed).containsExactly(CredentialId.of("cred_c"));
  }

  @Test
  void revokeMintedTracksAndRevokesSurplusCredential() {
    FlakyProvisioner provisioner = new FlakyProvisioner();
    InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());

    binding.revokeMinted(issued("cred_s", "v", null));

    assertThat(provisioner.revoked).containsExactly(CredentialId.of("cred_s"));
    assertThat(store.outstanding()).isEmpty();
  }

  @Test
  void interruptionDuringRetryBackoffStopsRevoking() {
    FlakyProvisioner provisioner = new FlakyProvisioner();
    provisioner.failuresBeforeSuccess = 99;
    RecordingStore store = new RecordingStore();
    CredentialBinding binding = new CredentialBinding(provisioner, store, Clock.systemUTC());
    JobRecord rec = record();
    binding.attach(rec, List.of(issued("cred_1", "v", null)));

    Thread.currentThread().interrupt();
    try {
      binding.revokeAll(rec);
      // Interrupted during the backoff sleep: gave up before exhausting all attempts.
      assertThat(provisioner.revokeAttempts.get()).isEqualTo(1);
      assertThat(store.failed).isEmpty();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear flag for other tests
    }
  }
}
