package dev.arcp.tck;

import dev.arcp.client.ArcpClient;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.CredentialRevocationStore;

/**
 * SPI implemented by downstream runtime impls under test. The provider is responsible for spinning
 * up a runtime against which an {@link ArcpClient} is connected; the harness owns the client
 * lifecycle.
 */
public interface TckProvider extends AutoCloseable {

  /**
   * Constructs and connects a fresh {@link ArcpClient} for one assertion.
   *
   * @return a connected client whose lifecycle the harness owns
   * @throws Exception if the runtime under test or the client cannot be brought up
   */
  ArcpClient connect() throws Exception;

  /**
   * Constructs a client against a runtime configured for provisioned credential assertions (§9.8).
   * The default implementation throws {@link UnsupportedOperationException}, which makes {@link
   * ConformanceSuite} skip the credential lifecycle test for this provider.
   *
   * @param provisioner issues credentials for jobs whose lease grants {@code model.use}
   * @param store records issued credentials so the suite can assert they are revoked
   * @return a connected client backed by the credential-provisioning runtime
   * @throws Exception if the runtime under test or the client cannot be brought up
   */
  default ArcpClient connectWithProvisionedCredentials(
      CredentialProvisioner provisioner, CredentialRevocationStore store) throws Exception {
    throw new UnsupportedOperationException("provisioned credentials not supported by provider");
  }

  /** Tear down any per-test runtime resources. */
  @Override
  default void close() throws Exception {}
}
