package dev.arcp.runtime.credentials;

import dev.arcp.core.credentials.Credential;
import org.jspecify.annotations.Nullable;

public record IssuedCredential(Credential wire, @Nullable String providerHandle) {}
