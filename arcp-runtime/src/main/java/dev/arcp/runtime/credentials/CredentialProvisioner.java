package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.agent.JobContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Plug-in point for upstream-specific provisioned credential issue/revoke. */
public interface CredentialProvisioner {

  /**
   * Mints credentials for one job, each pre-constrained to the job's lease so the upstream becomes
   * the enforcement boundary (§9.8). The runtime surfaces the issued credentials in {@code
   * job.accepted.payload.credentials} (§9.8.1).
   *
   * @param lease the granted lease the credentials must be scoped at or below
   * @param constraints the lease constraints (budget, model, {@code expires_at}) to bake in
   * @param ctx the job the credentials are being issued for
   * @return a future completing with the issued credentials; may be empty when the upstream
   *     requires none
   */
  CompletableFuture<List<IssuedCredential>> issue(
      Lease lease, LeaseConstraints constraints, JobContext ctx);

  /**
   * Revokes a previously issued credential at the upstream (§9.8.2). The runtime calls this when
   * the job reaches a terminal state or after rotation, retrying on transient failure.
   *
   * @param id the id of the credential to revoke
   * @return a future completing when revocation succeeds, or exceptionally on failure
   */
  CompletableFuture<Void> revoke(CredentialId id);
}
