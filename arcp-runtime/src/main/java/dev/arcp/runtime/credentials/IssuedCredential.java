package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.Credential;
import org.jspecify.annotations.Nullable;

/**
 * A credential minted by a {@link CredentialProvisioner}, pairing the §9.8.1 wire object with the
 * provisioner-private handle needed to revoke it at the upstream.
 *
 * @param wire the credential as surfaced in {@code job.accepted.payload.credentials} (§9.8.1)
 * @param providerHandle the upstream revocation handle, or {@code null} when the credential {@code
 *     id} itself suffices
 */
public record IssuedCredential(Credential wire, @Nullable String providerHandle) {}
