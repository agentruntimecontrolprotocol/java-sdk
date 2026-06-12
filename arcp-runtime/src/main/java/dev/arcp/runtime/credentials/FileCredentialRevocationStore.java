package dev.arcp.runtime.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.wire.ArcpMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable {@link CredentialRevocationStore} backed by an append-only JSON-lines log. Each {@code
 * record}/{@code revoke} entry is appended and fsynced before the call returns; on construction the
 * log is replayed to rebuild the outstanding set, so a restarted runtime can resume revoking
 * credentials issued before a crash (§9.8.2).
 */
public final class FileCredentialRevocationStore
    implements CredentialRevocationStore, AutoCloseable {
  private final Path path;
  private final ObjectMapper mapper;
  private final Map<CredentialId, String> outstanding = new LinkedHashMap<>();
  private final RandomAccessFile writer;

  /**
   * Opens the store at {@code path} (creating the file and parent directories if absent) using the
   * shared wire mapper.
   *
   * @param path the log file location
   */
  public FileCredentialRevocationStore(Path path) {
    this(path, ArcpMapper.shared());
  }

  /**
   * Opens the store at {@code path} (creating the file and parent directories if absent), replaying
   * any existing log entries to rebuild the outstanding set.
   *
   * @param path the log file location
   * @param mapper the Jackson mapper used to read and write log entries
   * @throws IllegalStateException if the log cannot be created, read, or opened for append
   */
  public FileCredentialRevocationStore(Path path, ObjectMapper mapper) {
    this.path = path;
    this.mapper = mapper;
    load();
    try {
      this.writer = new RandomAccessFile(path.toFile(), "rwd");
      this.writer.seek(this.writer.length());
    } catch (IOException e) {
      throw new IllegalStateException("could not open credential revocation store " + path, e);
    }
  }

  @Override
  public synchronized void close() {
    try {
      writer.close();
    } catch (IOException e) {
      throw new IllegalStateException("could not close credential revocation store " + path, e);
    }
  }

  @Override
  public synchronized void record(CredentialId id, String providerHandle) {
    outstanding.put(id, providerHandle);
    append("record", id, providerHandle);
  }

  @Override
  public synchronized void markRevoked(CredentialId id) {
    outstanding.remove(id);
    append("revoke", id, "");
  }

  @Override
  public synchronized List<Outstanding> outstanding() {
    return outstanding.entrySet().stream()
        .map(entry -> new Outstanding(entry.getKey(), entry.getValue()))
        .toList();
  }

  private void load() {
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      if (!Files.exists(path)) {
        Files.createFile(path);
        return;
      }
      try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.isBlank()) {
            apply(mapper.readTree(line));
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not load credential revocation store " + path, e);
    }
  }

  private void apply(JsonNode event) {
    CredentialId id = CredentialId.of(event.path("id").asText());
    if ("record".equals(event.path("op").asText())) {
      outstanding.put(id, event.path("provider_handle").asText());
    } else if ("revoke".equals(event.path("op").asText())) {
      outstanding.remove(id);
    }
  }

  private void append(String op, CredentialId id, String providerHandle) {
    ObjectNode event = mapper.createObjectNode().put("op", op).put("id", id.value());
    if (!providerHandle.isEmpty()) {
      event.put("provider_handle", providerHandle);
    }
    try {
      String line = mapper.writeValueAsString(event) + "\n";
      byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
      writer.write(bytes);
      writer.getFD().sync();
    } catch (IOException e) {
      throw new IllegalStateException("could not append credential revocation event", e);
    }
  }
}
