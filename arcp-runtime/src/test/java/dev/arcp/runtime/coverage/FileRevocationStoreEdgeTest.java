package dev.arcp.runtime.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.credentials.CredentialId;
import dev.arcp.runtime.credentials.FileCredentialRevocationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Load/append IO paths for FileCredentialRevocationStore (#33). */
class FileRevocationStoreEdgeTest {

  @Test
  void loadSkipsBlankLinesAndUnknownOps(@TempDir Path dir) throws Exception {
    Path path = dir.resolve("revocations.jsonl");
    Files.writeString(
        path,
        """
        {"op":"record","id":"cred_1","provider_handle":"h1"}

        {"op":"noop","id":"cred_1"}
        {"op":"record","id":"cred_2","provider_handle":"h2"}
        {"op":"revoke","id":"cred_2"}
        """);
    try (FileCredentialRevocationStore store = new FileCredentialRevocationStore(path)) {
      List<FileCredentialRevocationStore.Outstanding> outstanding = store.outstanding();
      assertThat(outstanding).hasSize(1);
      assertThat(outstanding.getFirst().id()).isEqualTo(CredentialId.of("cred_1"));
      assertThat(outstanding.getFirst().providerHandle()).isEqualTo("h1");
    }
  }

  @Test
  void recordAndRevokeRoundTripAcrossReload(@TempDir Path dir) {
    Path path = dir.resolve("nested").resolve("revocations.jsonl");
    try (FileCredentialRevocationStore store = new FileCredentialRevocationStore(path)) {
      store.record(CredentialId.of("cred_a"), "ha");
      store.record(CredentialId.of("cred_b"), "hb");
      store.markRevoked(CredentialId.of("cred_a"));
    }
    try (FileCredentialRevocationStore reloaded = new FileCredentialRevocationStore(path)) {
      assertThat(reloaded.outstanding()).hasSize(1);
      assertThat(reloaded.outstanding().getFirst().id()).isEqualTo(CredentialId.of("cred_b"));
    }
  }

  @Test
  void singleSegmentRelativePathHasNoParentDirectory() throws Exception {
    Path path = Path.of("revocation-coverage-" + UUID.randomUUID() + ".jsonl");
    try {
      try (FileCredentialRevocationStore store = new FileCredentialRevocationStore(path)) {
        store.record(CredentialId.of("cred_rel"), "h");
        assertThat(store.outstanding()).hasSize(1);
      }
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void appendAfterCloseFailsLoudly(@TempDir Path dir) {
    Path path = dir.resolve("closed.jsonl");
    FileCredentialRevocationStore store = new FileCredentialRevocationStore(path);
    store.close();
    assertThatThrownBy(() -> store.record(CredentialId.of("cred_x"), "h"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not append");
  }

  @Test
  void unreadableStoreFailsAtLoad(@TempDir Path dir) {
    // A directory in place of the journal file makes the initial load fail.
    assertThatThrownBy(() -> new FileCredentialRevocationStore(dir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not load");
  }

  @Test
  void readOnlyJournalFailsAtWriterOpen(@TempDir Path dir) throws Exception {
    Path path = dir.resolve("readonly.jsonl");
    Files.createFile(path);
    java.io.File file = path.toFile();
    assertThat(file.setWritable(false)).isTrue();
    try {
      assertThatThrownBy(() -> new FileCredentialRevocationStore(path))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("could not open");
    } finally {
      assertThat(file.setWritable(true)).isTrue();
    }
  }
}
