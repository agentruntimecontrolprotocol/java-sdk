package dev.arcp.examples.provisionedcredentials;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialConstraints;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.IssuedCredential;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    AtomicBoolean revoked = new AtomicBoolean();
    CredentialProvisioner provisioner =
        new CredentialProvisioner() {
          @Override
          public CompletableFuture<List<IssuedCredential>> issue(
              Lease lease,
              dev.arcp.core.lease.LeaseConstraints constraints,
              dev.arcp.runtime.agent.JobContext ctx) {
            Credential credential =
                new Credential(
                    CredentialId.of("demo_cred_1"),
                    CredentialScheme.BEARER,
                    "demo-token",
                    "https://llm-gateway.example.test/v1",
                    "fast",
                    new CredentialConstraints(
                        lease.patterns("cost.budget"),
                        lease.patterns("model.use"),
                        constraints.expiresAt()));
            return CompletableFuture.completedFuture(
                List.of(new IssuedCredential(credential, "demo_cred_1")));
          }

          @Override
          public CompletableFuture<Void> revoke(CredentialId id) {
            revoked.set(true);
            return CompletableFuture.completedFuture(null);
          }
        };

    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .credentialProvisioner(provisioner)
            .credentialRevocationStore(new InMemoryCredentialRevocationStore())
            .agent(
                "llm-task",
                "1.0.0",
                (input, ctx) -> {
                  assert input.credentials().size() == 1;
                  return JobOutcome.Success.inline(input.payload());
                })
            .build();
    runtime.accept(pair.runtime());

    try (runtime;
        ArcpClient client = ArcpClient.builder(pair.client()).build()) {
      client.connect(Duration.ofSeconds(5));
      Lease lease =
          Lease.builder()
              .allow("model.use", "tier-fast/*")
              .allow("cost.budget", "USD:5.00")
              .build();
      JobHandle handle =
          client.submit(
              ArcpClient.jobSubmit(
                  "llm-task@1.0.0",
                  JsonNodeFactory.instance.objectNode(),
                  lease,
                  null,
                  null,
                  null));
      assert handle.credentials().orElseThrow().size() == 1;
      handle.result().get(5, TimeUnit.SECONDS);
      assert revoked.get();
      System.out.println("OK provisioned-credentials");
    }
  }
}
