package dev.fizzpop.arcp;

/**
 * Library identity. Populated at build time via the project version (see
 * {@code lib/build.gradle.kts}). Implements the {@code runtime.kind} /
 * {@code runtime.version} block in {@code session.accepted} (RFC §8.3).
 */
public final class Version {

	/**
	 * Library kind, advertised as {@code runtime.kind} in {@code session.accepted}.
	 */
	public static final String IMPL_KIND = "arcp-java";

	/**
	 * Library version, advertised as {@code runtime.version} in
	 * {@code session.accepted}.
	 */
	public static final String IMPL_VERSION = "0.1.0-SNAPSHOT";

	private Version() {
	}
}
