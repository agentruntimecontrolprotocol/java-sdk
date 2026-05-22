package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.agent.JobContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Plug-in point for upstream-specific provisioned credential issue/revoke. */
public interface CredentialProvisioner {
  CompletableFuture<List<IssuedCredential>> issue(
      Lease lease, LeaseConstraints constraints, JobContext ctx);

  CompletableFuture<Void> revoke(CredentialId id);
}
