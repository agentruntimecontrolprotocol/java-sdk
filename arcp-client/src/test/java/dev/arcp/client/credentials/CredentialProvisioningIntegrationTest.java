package dev.arcp.client.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.error.UpstreamBudgetExhaustedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.IssuedCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CredentialProvisioningIntegrationTest {
    @Test
    void credentialsSurfaceAndRevokeOnSuccess() throws Exception {
        CountingProvisioner provisioner = new CountingProvisioner();
        InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = runtime(pair[0], provisioner, store, "agent", "1.0.0",
                (input, ctx) -> {
                    assertThat(input.credentials()).hasSize(1);
                    assertThat(ctx.credentials()).hasSize(1);
                    return JobOutcome.Success.inline(input.payload());
                });

        try (runtime; ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "agent@1.0.0",
                    JsonNodeFactory.instance.objectNode(),
                    lease(),
                    null,
                    null,
                    null));

            assertThat(handle.credentials()).isPresent();
            assertThat(handle.credentials().orElseThrow()).extracting(Credential::value)
                    .containsExactly("secret-1");
            assertThat(handle.result().get(5, TimeUnit.SECONDS).finalStatus())
                    .isEqualTo(JobResult.SUCCESS);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(provisioner.revokeCount.get()).isEqualTo(1));
            assertThat(store.outstanding()).isEmpty();
        }
    }

    @Test
    void credentialsRevokeOnCancelAndError() throws Exception {
        assertTerminalRevokes((input, ctx) -> {
            while (!ctx.cancelled()) {
                Thread.sleep(10);
            }
            return JobOutcome.Success.inline(input.payload());
        }, true);

        assertTerminalRevokes((input, ctx) ->
                new JobOutcome.Failure(ErrorCode.INTERNAL_ERROR, "boom"), false);
    }

    @Test
    void credentialsRevokeOnLeaseTimeout() throws Exception {
        CountingProvisioner provisioner = new CountingProvisioner();
        InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = runtime(pair[0], provisioner, store, "agent", "1.0.0",
                (input, ctx) -> {
                    Thread.sleep(Duration.ofSeconds(10));
                    return JobOutcome.Success.inline(input.payload());
                });

        try (runtime; ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "agent@1.0.0",
                    JsonNodeFactory.instance.objectNode(),
                    lease(),
                    LeaseConstraints.of(Instant.now().plusMillis(100)),
                    null,
                    null));
            try {
                handle.result().get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                // Expiry is reported as a terminal job error.
            }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(provisioner.revokeCount.get()).isEqualTo(1));
            assertThat(store.outstanding()).isEmpty();
        }
    }

    @Test
    void upstreamBudgetExhaustionBecomesBudgetExhausted() {
        CredentialProvisioner provisioner = new CredentialProvisioner() {
            @Override
            public CompletableFuture<List<IssuedCredential>> issue(
                    Lease lease, LeaseConstraints constraints, dev.arcp.runtime.agent.JobContext ctx) {
                CompletableFuture<List<IssuedCredential>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new UpstreamBudgetExhaustedException("spent", "{}"));
                return failed;
            }

            @Override
            public CompletableFuture<Void> revoke(CredentialId id) {
                return CompletableFuture.completedFuture(null);
            }
        };
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = ArcpRuntime.builder()
                .credentialProvisioner(provisioner)
                .credentialRevocationStore(new InMemoryCredentialRevocationStore())
                .agent("agent", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
                .build();
        runtime.accept(pair[0]);

        try (runtime; ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.submit(ArcpClient.jobSubmit(
                    "agent@1.0.0",
                    JsonNodeFactory.instance.objectNode(),
                    lease(),
                    null,
                    null,
                    null)))
                    .hasRootCauseInstanceOf(BudgetExhaustedException.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertTerminalRevokes(
            dev.arcp.runtime.agent.Agent agent, boolean cancel) throws Exception {
        CountingProvisioner provisioner = new CountingProvisioner();
        InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
        MemoryTransport[] pair = MemoryTransport.pair();
        ArcpRuntime runtime = runtime(pair[0], provisioner, store, "agent", "1.0.0", agent);

        try (runtime; ArcpClient client = ArcpClient.builder(pair[1]).build()) {
            client.connect(Duration.ofSeconds(5));
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "agent@1.0.0",
                    JsonNodeFactory.instance.objectNode(),
                    lease(),
                    null,
                    null,
                    null));
            if (cancel) {
                handle.cancel();
            }
            try {
                handle.result().get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                // Cancellation and agent failure both surface through the result future.
            }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(provisioner.revokeCount.get()).isEqualTo(1));
            assertThat(store.outstanding()).isEmpty();
        }
    }

    private static ArcpRuntime runtime(
            MemoryTransport transport,
            CredentialProvisioner provisioner,
            InMemoryCredentialRevocationStore store,
            String name,
            String version,
            dev.arcp.runtime.agent.Agent agent) {
        ArcpRuntime runtime = ArcpRuntime.builder()
                .credentialProvisioner(provisioner)
                .credentialRevocationStore(store)
                .features(EnumSet.allOf(Feature.class))
                .agent(name, version, agent)
                .build();
        runtime.accept(transport);
        return runtime;
    }

    private static Lease lease() {
        return Lease.builder()
                .allow("model.use", "tier-fast/*")
                .allow("cost.budget", "USD:5.00")
                .build();
    }

    private static final class CountingProvisioner implements CredentialProvisioner {
        final AtomicInteger issueCount = new AtomicInteger();
        final AtomicInteger revokeCount = new AtomicInteger();

        @Override
        public CompletableFuture<List<IssuedCredential>> issue(
                Lease lease, LeaseConstraints constraints, dev.arcp.runtime.agent.JobContext ctx) {
            int count = issueCount.incrementAndGet();
            Credential credential = new Credential(
                    CredentialId.of("cred_" + count),
                    CredentialScheme.BEARER,
                    "secret-" + count,
                    "https://llm.example.test/v1",
                    "fast",
                    null);
            return CompletableFuture.completedFuture(List.of(new IssuedCredential(credential, null)));
        }

        @Override
        public CompletableFuture<Void> revoke(CredentialId id) {
            revokeCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}
