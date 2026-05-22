package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCredentialRevocationStore implements CredentialRevocationStore {
    private final ConcurrentHashMap<CredentialId, String> outstanding = new ConcurrentHashMap<>();

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
