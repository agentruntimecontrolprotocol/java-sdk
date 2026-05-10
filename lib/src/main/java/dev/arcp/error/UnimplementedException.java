package dev.arcp.error;

import java.util.Objects;

/**
 * Thrown when a feature is recognized by the SDK but explicitly deferred from
 * v0.1 (RFC §X.Y). Maps to canonical {@link ErrorCode#UNIMPLEMENTED}.
 */
public final class UnimplementedException extends ARCPException {

	private static final long serialVersionUID = 1L;

	private final String section;

	/**
	 * @param section
	 *            RFC section reference, e.g. {@code "§10.6"}.
	 * @param detail
	 *            one-line explanation of what is unimplemented.
	 */
	public UnimplementedException(String section, String detail) {
		super(ErrorCode.UNIMPLEMENTED,
				Objects.requireNonNull(section, "section") + ": " + Objects.requireNonNull(detail, "detail"));
		this.section = section;
	}

	/** @return RFC section reference. */
	public String section() {
		return section;
	}
}
