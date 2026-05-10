package dev.arcp.error;

/** Operation refused by permission policy (RFC §15.4). */
public final class PermissionDeniedException extends ARCPException {

	private static final long serialVersionUID = 1L;

	public PermissionDeniedException(String message) {
		super(ErrorCode.PERMISSION_DENIED, message);
	}
}
