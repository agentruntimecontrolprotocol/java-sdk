package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.CredentialId;
import java.util.List;

/**
 * Tracks issued credentials from mint until their upstream revocation succeeds, so spend authority
 * never dangles silently (§9.8.2). Durable implementations let a restarted runtime discover and
 * revoke credentials issued before a crash.
 */
public interface CredentialRevocationStore {

  /**
   * Records that a credential was issued and is awaiting revocation.
   *
   * @param id the credential id (§9.8.1)
   * @param providerHandle the upstream handle needed to revoke the credential later
   */
  void record(CredentialId id, String providerHandle);

  /**
   * Marks a credential as successfully revoked at the upstream, removing it from the outstanding
   * set.
   *
   * @param id the credential id
   */
  void markRevoked(CredentialId id);

  /**
   * Notes that revocation failed for this credential after exhausting retries. Default
   * implementation is a no-op so existing implementations continue to compile.
   *
   * @param id the credential id whose revocation failed
   * @param cause the failure from the final revocation attempt
   */
  default void markRevocationFailed(CredentialId id, Throwable cause) {
    // No-op default; durable stores may persist the failure for asynchronous recovery.
  }

  /**
   * Returns credentials that were recorded but not yet marked revoked.
   *
   * @return the outstanding credentials, oldest first where the implementation preserves order
   */
  List<Outstanding> outstanding();

  /**
   * A credential awaiting revocation.
   *
   * @param id the credential id (§9.8.1)
   * @param providerHandle the upstream handle needed to revoke the credential
   */
  record Outstanding(CredentialId id, String providerHandle) {}
}
