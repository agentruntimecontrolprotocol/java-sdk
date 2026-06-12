package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-durable {@link CredentialRevocationStore} backed by a concurrent map. Suitable for tests and
 * runtimes that do not advertise {@code provisioned_credentials}; outstanding credentials are lost
 * on process exit, so production deployments should prefer {@link FileCredentialRevocationStore} or
 * another durable store (§9.8.2).
 */
public final class InMemoryCredentialRevocationStore implements CredentialRevocationStore {
  private final ConcurrentHashMap<CredentialId, String> outstanding = new ConcurrentHashMap<>();

  /** Creates an empty store. */
  public InMemoryCredentialRevocationStore() {}

  @Override
  public void record(CredentialId id, String providerHandle) {
    outstanding.put(id, providerHandle);
  }

  @Override
  public void markRevoked(CredentialId id) {
    outstanding.remove(id);
  }

  @Override
  public List<Outstanding> outstanding() {
    return outstanding.entrySet().stream()
        .map(entry -> new Outstanding(entry.getKey(), entry.getValue()))
        .toList();
  }
}
