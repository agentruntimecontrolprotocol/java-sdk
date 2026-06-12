package dev.arcp.runtime.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.events.CredentialRotatedBody;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.runtime.session.JobRecord;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ties §9.8 provisioned credentials to a job's lifecycle: records every issued credential in the
 * {@link CredentialRevocationStore}, attaches credentials to the {@link JobRecord}, emits {@code
 * credential_rotated} status events on rotation, and revokes with bounded retry when the job
 * terminates (§9.8.2).
 */
public final class CredentialBinding {
  private static final Logger log = LoggerFactory.getLogger(CredentialBinding.class);
  private static final int MAX_REVOKE_ATTEMPTS = 3;

  private final CredentialProvisioner provisioner;
  private final CredentialRevocationStore store;

  @SuppressWarnings("unused")
  private final Clock clock;

  private final ObjectMapper mapper;
  private final BiConsumer<JobRecord, EventBody> eventSink;

  /**
   * Creates a binding that discards rotation events.
   *
   * @param provisioner the backend that revokes credentials at the upstream
   * @param store the store tracking credentials until revocation succeeds
   * @param clock the runtime clock
   */
  public CredentialBinding(
      CredentialProvisioner provisioner, CredentialRevocationStore store, Clock clock) {
    this(provisioner, store, clock, (record, body) -> {});
  }

  /**
   * Creates a binding that publishes {@code credential_rotated} status events through {@code
   * eventSink} (§9.8.2).
   *
   * @param provisioner the backend that revokes credentials at the upstream
   * @param store the store tracking credentials until revocation succeeds
   * @param clock the runtime clock
   * @param eventSink the sink receiving rotation status events for a job
   */
  public CredentialBinding(
      CredentialProvisioner provisioner,
      CredentialRevocationStore store,
      Clock clock,
      BiConsumer<JobRecord, EventBody> eventSink) {
    this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.mapper = ArcpMapper.shared();
    this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
  }

  /**
   * Records freshly issued credentials in the revocation store and attaches them to {@code record},
   * returning the wire objects for {@code job.accepted.payload.credentials} (§9.8.1).
   *
   * @param record the job the credentials were issued for
   * @param issued the credentials minted by the provisioner; copied defensively
   * @return the wire form of each attached credential, in issue order
   */
  public List<Credential> attach(JobRecord record, List<IssuedCredential> issued) {
    List<IssuedCredential> copy = List.copyOf(issued);
    for (IssuedCredential credential : copy) {
      store.record(
          credential.wire().id(),
          credential.providerHandle() != null
              ? credential.providerHandle()
              : credential.wire().id().value());
    }
    record.setCredentials(copy);
    return copy.stream().map(IssuedCredential::wire).toList();
  }

  /**
   * Replaces credential {@code id} on the job with {@code next}, revokes the prior value, records
   * the replacement, and emits a {@code credential_rotated} status event (§9.8.2).
   *
   * @param record the job whose credential is rotating
   * @param id the id of the credential being rotated
   * @param next the replacement credential
   */
  public void rotate(JobRecord record, CredentialId id, IssuedCredential next) {
    IssuedCredential prior = record.replaceCredential(id, next);
    if (prior != null) {
      revoke(prior);
    }
    store.record(
        next.wire().id(),
        next.providerHandle() != null ? next.providerHandle() : next.wire().id().value());
    eventSink.accept(
        record,
        new StatusEvent(
            "credential_rotated",
            null,
            mapper.valueToTree(new CredentialRotatedBody(next.wire().id(), next.wire().value()))));
  }

  /**
   * Revokes every credential still attached to {@code record}; invoked when the job reaches a
   * terminal state, regardless of how termination occurred (§9.8.2).
   *
   * @param record the terminated job
   */
  public void revokeAll(JobRecord record) {
    for (IssuedCredential credential : record.drainCredentials()) {
      revoke(credential);
    }
  }

  /**
   * Records then revokes a credential that was minted upstream but not attached to a job (e.g.
   * surplus credentials returned during rotation), so its spend authority is tracked and released
   * rather than dangling (§14, #98).
   *
   * @param credential the unattached credential to track and revoke
   */
  public void revokeMinted(IssuedCredential credential) {
    store.record(
        credential.wire().id(),
        credential.providerHandle() != null
            ? credential.providerHandle()
            : credential.wire().id().value());
    revoke(credential);
  }

  private void revoke(IssuedCredential credential) {
    CredentialId id = credential.wire().id();
    for (int attempt = 1; attempt <= MAX_REVOKE_ATTEMPTS; attempt++) {
      try {
        provisioner.revoke(id).join();
        store.markRevoked(id);
        return;
      } catch (CompletionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (attempt == MAX_REVOKE_ATTEMPTS) {
          log.warn("credential revoke failed after {} attempts for {}", attempt, id, cause);
          store.markRevocationFailed(id, cause);
          return;
        }
        log.debug("credential revoke attempt {} failed for {}", attempt, id, cause);
      } catch (RuntimeException e) {
        if (attempt == MAX_REVOKE_ATTEMPTS) {
          log.warn("credential revoke failed after {} attempts for {}", attempt, id, e);
          store.markRevocationFailed(id, e);
          return;
        }
        log.debug("credential revoke attempt {} failed for {}", attempt, id, e);
      }
      try {
        Thread.sleep(100L * attempt);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
