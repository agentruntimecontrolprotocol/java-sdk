package dev.arcp.auth;

import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory bearer-token validator. Maps each accepted token to a
 * {@link Principal}; rejects everything else with
 * {@link ErrorCode#UNAUTHENTICATED}. Suitable for tests and reference runtimes;
 * not a substitute for a real identity provider.
 */
public final class StaticBearerValidator implements CredentialValidator {

	private final Map<String, Principal> tokens;

	public StaticBearerValidator(Map<String, Principal> tokens) {
		this.tokens = Map.copyOf(Objects.requireNonNull(tokens, "tokens"));
	}

	@Override
	public Principal validate(Credentials credentials) {
		if (!(credentials instanceof Credentials.BearerCredentials bearer)) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "bearer scheme required");
		}
		Principal p = tokens.get(bearer.token());
		if (p == null) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "unknown bearer token");
		}
		return p;
	}
}
