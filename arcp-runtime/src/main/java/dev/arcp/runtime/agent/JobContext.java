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

  /**
   * Emits a job event for this job (§8). Thread-safe; ordering is preserved per job.
   *
   * @param body the event body to wrap in a {@code job.event} envelope
   */
  void emit(EventBody body);

  /**
   * Reports whether cancellation has been requested for this job.
   *
   * @return {@code true} if a {@code job.cancel} or session close has been observed (§7.4)
   */
  boolean cancelled();

  /**
   * Authorizes an operation against the active lease (§9.3 / §9.5 / §9.6). Throws on lease subset
   * violation, expired lease, or exhausted budget.
   *
   * @param namespace the capability namespace, e.g. {@code fs.read} or {@code model.use}
   * @param pattern the concrete value being attempted, matched against the lease's patterns
   * @throws PermissionDeniedException if no lease pattern in {@code namespace} permits {@code
   *     pattern} (§9.3)
   * @throws LeaseExpiredException if the lease's {@code expires_at} has passed (§9.5)
   * @throws BudgetExhaustedException if a budgeted counter has reached zero or below (§9.6)
   */
  void authorize(String namespace, String pattern)
      throws PermissionDeniedException, LeaseExpiredException, BudgetExhaustedException;

  /**
   * Returns the currently active provisioned credentials for this job (§9.8).
   *
   * @return the live credentials, or an empty list when none are provisioned
   */
  default List<Credential> credentials() {
    return List.of();
  }

  /**
   * Rotates an issued credential value and publishes a {@code credential_rotated} status event
   * (§9.8.2). The prior value is revoked promptly.
   *
   * @param id the id of the credential being rotated
   * @param newValue the replacement credential material
   */
  default void rotateCredential(CredentialId id, String newValue) {
    // Default is a no-op for runtimes without provisioned credentials.
  }
}
