package dev.arcp.extensions;

import java.util.regex.Pattern;

/**
 * Validates extension namespace strings per RFC §21.1.
 *
 * <p>
 * Accepted forms:
 * <ul>
 * <li>{@code arcpx.<vendor>.<name>.v<n>} — canonical ARCP extension form.</li>
 * <li>Reverse-DNS, e.g. {@code com.example.foo.v1}.</li>
 * </ul>
 * Both end with a {@code .v<n>} suffix where {@code n} is a positive integer.
 */
public final class ExtensionNamespace {

	private static final Pattern ARCPX = Pattern
			.compile("^arcpx\\.[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+\\.v[0-9]+$");

	private static final Pattern REVERSE_DNS = Pattern
			.compile("^(?!arcpx\\.)[a-z][a-z0-9]*(?:\\.[a-z0-9][a-z0-9_-]*)+\\.v[0-9]+$");

	private ExtensionNamespace() {
	}

	/** @return {@code true} iff {@code ns} matches either accepted form. */
	public static boolean isValid(String ns) {
		if (ns == null)
			return false;
		return ARCPX.matcher(ns).matches() || REVERSE_DNS.matcher(ns).matches();
	}

	/**
	 * @throws IllegalArgumentException
	 *             if {@code ns} does not match an accepted form.
	 */
	public static String require(String ns) {
		if (!isValid(ns)) {
			throw new IllegalArgumentException("invalid extension namespace: " + ns);
		}
		return ns;
	}
}
