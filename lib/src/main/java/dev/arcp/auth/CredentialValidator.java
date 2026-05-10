package dev.arcp.auth;

/**
 * Pluggable validator for credentials presented during a session handshake (RFC
 * §8). Returns an authenticated {@link Principal} or throws.
 *
 * <p>
 * The SDK provides reference implementations: {@link StaticBearerValidator} and
 * {@link JwtValidator}. Production users supply their own.
 */
@FunctionalInterface
public interface CredentialValidator {

	/**
	 * @param credentials
	 *            caller-presented credentials.
	 * @return the authenticated principal on success.
	 * @throws dev.arcp.error.ARCPException
	 *             with {@code UNAUTHENTICATED} when the credentials are
	 *             unacceptable.
	 */
	Principal validate(Credentials credentials);
}
