package dev.arcp.auth;

/**
 * An authenticated session principal. {@link #subject} is the logical identity
 * (e.g. user id, service account); {@link #trustLevel} maps to one of
 * {@code untrusted | constrained | trusted | privileged} (RFC §15.3).
 */
public record Principal(String subject, String trustLevel) {

	/**
	 * Anonymous principal used when the {@code anonymous} capability is in force.
	 */
	public static Principal anonymous() {
		return new Principal("anonymous", "untrusted");
	}
}
