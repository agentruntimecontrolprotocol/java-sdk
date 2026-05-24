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

public final class CredentialBinding {
  private static final Logger log = LoggerFactory.getLogger(CredentialBinding.class);
  private static final int MAX_REVOKE_ATTEMPTS = 3;

  private final CredentialProvisioner provisioner;
  private final CredentialRevocationStore store;

  @SuppressWarnings("unused")
  private final Clock clock;

  private final ObjectMapper mapper;
  private final BiConsumer<JobRecord, EventBody> eventSink;

  public CredentialBinding(
      CredentialProvisioner provisioner, CredentialRevocationStore store, Clock clock) {
    this(provisioner, store, clock, (record, body) -> {});
  }

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

  public void revokeAll(JobRecord record) {
    for (IssuedCredential credential : record.drainCredentials()) {
      revoke(credential);
    }
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
