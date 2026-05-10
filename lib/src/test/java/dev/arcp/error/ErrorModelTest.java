package dev.arcp.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.ids.LeaseId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ErrorModelTest {

	@Test
	void defaultRetryabilityMatchesRfc18_3() {
		assertThat(ErrorCode.UNAVAILABLE.defaultRetryable()).isTrue();
		assertThat(ErrorCode.DEADLINE_EXCEEDED.defaultRetryable()).isTrue();
		assertThat(ErrorCode.HEARTBEAT_LOST.defaultRetryable()).isTrue();
		assertThat(ErrorCode.RESOURCE_EXHAUSTED.defaultRetryable()).isTrue();
		assertThat(ErrorCode.ABORTED.defaultRetryable()).isTrue();

		assertThat(ErrorCode.INVALID_ARGUMENT.defaultRetryable()).isFalse();
		assertThat(ErrorCode.PERMISSION_DENIED.defaultRetryable()).isFalse();
		assertThat(ErrorCode.NOT_FOUND.defaultRetryable()).isFalse();
		assertThat(ErrorCode.UNIMPLEMENTED.defaultRetryable()).isFalse();
		assertThat(ErrorCode.DATA_LOSS.defaultRetryable()).isFalse();
		assertThat(ErrorCode.LEASE_EXPIRED.defaultRetryable()).isFalse();
		assertThat(ErrorCode.LEASE_REVOKED.defaultRetryable()).isFalse();
	}

	@Test
	void wireFormIsCanonical() {
		assertThat(ErrorCode.PERMISSION_DENIED.wire()).isEqualTo("PERMISSION_DENIED");
		assertThat(ErrorCode.fromWire("PERMISSION_DENIED")).isEqualTo(ErrorCode.PERMISSION_DENIED);
	}

	@Test
	void arcpExceptionCarriesCodeAndCause() {
		Throwable root = new IllegalStateException("boom");
		ARCPException e = new ARCPException(ErrorCode.INTERNAL, "wrap", root);
		assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL);
		assertThat(e.retryable()).isTrue();
		assertThat(e.getCause()).isSameAs(root);
	}

	@Test
	void unimplementedExceptionRetainsSection() {
		UnimplementedException e = new UnimplementedException("§10.6", "scheduled jobs");
		assertThat(e.code()).isEqualTo(ErrorCode.UNIMPLEMENTED);
		assertThat(e.section()).isEqualTo("§10.6");
		assertThat(e.getMessage()).contains("§10.6").contains("scheduled jobs");
	}

	@Test
	void leaseExpiredCarriesIdAndInstant() {
		LeaseId leaseId = LeaseId.of("L1");
		Instant at = Instant.parse("2026-05-10T00:00:00Z");
		LeaseExpiredException e = new LeaseExpiredException(leaseId, at);
		assertThat(e.code()).isEqualTo(ErrorCode.LEASE_EXPIRED);
		assertThat(e.leaseId()).isEqualTo(leaseId);
		assertThat(e.expiredAt()).isEqualTo(at);
	}

	@Test
	void leaseRevokedCarriesId() {
		LeaseId leaseId = LeaseId.of("L2");
		LeaseRevokedException e = new LeaseRevokedException(leaseId);
		assertThat(e.code()).isEqualTo(ErrorCode.LEASE_REVOKED);
		assertThat(e.leaseId()).isEqualTo(leaseId);
	}

	@Test
	void permissionDeniedExceptionCode() {
		assertThat(new PermissionDeniedException("nope").code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
	}

	@Test
	void nullArgumentsRejected() {
		assertThatThrownBy(() -> new ARCPException(null, "x")).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ARCPException(ErrorCode.OK, null)).isInstanceOf(NullPointerException.class);
	}
}
