package dev.arcp.tck;

import dev.arcp.client.ArcpClient;

/**
 * SPI implemented by downstream runtime impls under test. The provider is
 * responsible for spinning up a runtime against which an {@link ArcpClient}
 * is connected; the harness owns the client lifecycle.
 */
public interface TckProvider {

    /** Construct and connect a fresh {@link ArcpClient} for one assertion. */
    ArcpClient connect() throws Exception;

    /** Tear down any per-test runtime resources. */
    default void close() throws Exception {}
}
