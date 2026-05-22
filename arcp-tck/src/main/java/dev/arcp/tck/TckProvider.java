package dev.arcp.tck;

import dev.arcp.client.ArcpClient;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.CredentialRevocationStore;

/**
 * SPI implemented by downstream runtime impls under test. The provider is
 * responsible for spinning up a runtime against which an {@link ArcpClient}
 * is connected; the harness owns the client lifecycle.
 */
public interface TckProvider extends AutoCloseable {

    /** Construct and connect a fresh {@link ArcpClient} for one assertion. */
    ArcpClient connect() throws Exception;

    /** Construct a client against a runtime configured for provisioned credential assertions. */
    default ArcpClient connectWithProvisionedCredentials(
            CredentialProvisioner provisioner, CredentialRevocationStore store) throws Exception {
        throw new UnsupportedOperationException("provisioned credentials not supported by provider");
    }

    /** Tear down any per-test runtime resources. */
    @Override
    default void close() throws Exception {}
}
