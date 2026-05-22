package dev.arcp.runtime.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arcp.core.credentials.CredentialId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialRevocationStoreTest {
    @Test
    void inMemoryStoreTracksOutstandingCredentials() {
        InMemoryCredentialRevocationStore store = new InMemoryCredentialRevocationStore();
        CredentialId id = CredentialId.of("cred_1");

        store.record(id, "handle-1");
        assertThat(store.outstanding()).containsExactly(
                new CredentialRevocationStore.Outstanding(id, "handle-1"));

        store.markRevoked(id);
        assertThat(store.outstanding()).isEmpty();
    }

    @Test
    void fileStoreReplaysOutstandingCredentials(@TempDir Path dir) {
        Path path = dir.resolve("revocations.jsonl");
        CredentialId first = CredentialId.of("cred_1");
        CredentialId second = CredentialId.of("cred_2");

        FileCredentialRevocationStore store = new FileCredentialRevocationStore(path);
        store.record(first, "handle-1");
        store.record(second, "handle-2");
        store.markRevoked(first);

        FileCredentialRevocationStore reloaded = new FileCredentialRevocationStore(path);
        assertThat(reloaded.outstanding()).containsExactly(
                new CredentialRevocationStore.Outstanding(second, "handle-2"));
    }
}
