package dev.arcp.error;

import dev.arcp.ids.LeaseId;
import java.time.Instant;
import java.util.Objects;

/** Operation attempted against an expired lease (RFC §15.5). */
public final class LeaseExpiredException extends ARCPException {

	private static final long serialVersionUID = 1L;

	private final LeaseId leaseId;
	private final Instant expiredAt;

	public LeaseExpiredException(LeaseId leaseId, Instant expiredAt) {
		super(ErrorCode.LEASE_EXPIRED, "lease " + Objects.requireNonNull(leaseId, "leaseId").asString() + " expired at "
				+ Objects.requireNonNull(expiredAt, "expiredAt"));
		this.leaseId = leaseId;
		this.expiredAt = expiredAt;
	}

	public LeaseId leaseId() {
		return leaseId;
	}

	public Instant expiredAt() {
		return expiredAt;
	}
}
