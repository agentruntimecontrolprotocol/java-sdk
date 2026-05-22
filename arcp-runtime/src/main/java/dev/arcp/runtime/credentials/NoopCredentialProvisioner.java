package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.agent.JobContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class NoopCredentialProvisioner implements CredentialProvisioner {
    public static final NoopCredentialProvisioner INSTANCE = new NoopCredentialProvisioner();

    private NoopCredentialProvisioner() {}

    @Override
    public CompletableFuture<List<IssuedCredential>> issue(
            Lease lease, LeaseConstraints constraints, JobContext ctx) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Void> revoke(CredentialId id) {
        return CompletableFuture.completedFuture(null);
    }
}
