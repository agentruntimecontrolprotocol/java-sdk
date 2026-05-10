package dev.arcp.error;

import dev.arcp.ids.LeaseId;
import java.util.Objects;

/** Operation attempted against a revoked lease (RFC §15.5). */
public final class LeaseRevokedException extends ARCPException {

	private static final long serialVersionUID = 1L;

	private final LeaseId leaseId;

	public LeaseRevokedException(LeaseId leaseId) {
		super(ErrorCode.LEASE_REVOKED, "lease " + Objects.requireNonNull(leaseId, "leaseId").asString() + " revoked");
		this.leaseId = leaseId;
	}

	public LeaseId leaseId() {
		return leaseId;
	}
}
