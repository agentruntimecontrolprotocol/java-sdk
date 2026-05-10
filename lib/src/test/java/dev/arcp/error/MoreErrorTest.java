package dev.arcp.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoreErrorTest {

	@Test
	void twoArgConstructor() {
		ARCPException e = new ARCPException(ErrorCode.UNAVAILABLE, "down");
		assertThat(e.code()).isEqualTo(ErrorCode.UNAVAILABLE);
		assertThat(e.getMessage()).isEqualTo("down");
		assertThat(e.getCause()).isNull();
		assertThat(e.retryable()).isTrue();
	}
}
