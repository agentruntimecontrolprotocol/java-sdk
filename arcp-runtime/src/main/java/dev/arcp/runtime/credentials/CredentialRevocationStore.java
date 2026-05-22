package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import java.util.List;

public interface CredentialRevocationStore {
  void record(CredentialId id, String providerHandle);

  void markRevoked(CredentialId id);

  List<Outstanding> outstanding();

  record Outstanding(CredentialId id, String providerHandle) {}
}
