package dev.arcp.runtime.agent;

import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.events.EventBody;
import java.util.List;

/** Per-job runtime handle passed to {@link Agent#run}. */
public interface JobContext {

  /** Emit a job event for this job. Thread-safe; ordering preserved per job. */
  void emit(EventBody body);

  /** {@code true} if a {@code job.cancel} or session close has been observed. */
  boolean cancelled();

  /**
   * Authorize an operation against the active lease (§9.3 / §9.5 / §9.6). Throws on lease subset
   * violation, expired lease, or exhausted budget.
   */
  void authorize(String namespace, String pattern)
      throws PermissionDeniedException, LeaseExpiredException, BudgetExhaustedException;

  /** Currently active provisioned credentials for this job. */
  default List<Credential> credentials() {
    return List.of();
  }

  /** Rotate an issued credential value and publish a credential_rotated status event. */
  default void rotateCredential(CredentialId id, String newValue) {
    // Default is a no-op for runtimes without provisioned credentials.
  }
}
