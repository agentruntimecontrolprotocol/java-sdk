package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import java.util.List;

public interface CredentialRevocationStore {
  void record(CredentialId id, String providerHandle);

  void markRevoked(CredentialId id);

  /**
   * Note that revocation failed for this credential after exhausting retries. Default
   * implementation is a no-op so existing implementations continue to compile.
   */
  default void markRevocationFailed(CredentialId id, Throwable cause) {
    // No-op default; durable stores may persist the failure for asynchronous recovery.
  }

  List<Outstanding> outstanding();

  record Outstanding(CredentialId id, String providerHandle) {}
}
