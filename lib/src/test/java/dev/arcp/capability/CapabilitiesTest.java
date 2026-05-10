package dev.arcp.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilitiesTest {

	@Test
	void noneIsAllFalse() {
		Capabilities n = Capabilities.NONE;
		assertThat(n.anonymous()).isFalse();
		assertThat(n.streaming()).isFalse();
		assertThat(n.humanInput()).isFalse();
	}

	@Test
	void referenceAdvertisesEverything() {
		Capabilities r = Capabilities.reference();
		assertThat(r.streaming()).isTrue();
		assertThat(r.permissions()).isTrue();
		assertThat(r.binaryEncoding()).contains("base64");
		assertThat(r.heartbeatRecovery()).isEqualTo("block");
	}

	@Test
	void intersectAndsBooleans() {
		Capabilities a = new Capabilities(true, true, false, true, true, true, true, "block", 30, List.of("base64"),
				Set.of("arcpx.x.y.v1"));
		Capabilities b = new Capabilities(false, true, true, true, false, true, true, "block", 60, List.of("base64"),
				Set.of("arcpx.x.y.v1", "arcpx.other.z.v1"));
		Capabilities out = CapabilityNegotiator.intersect(a, b);
		assertThat(out.anonymous()).isFalse();
		assertThat(out.streaming()).isTrue();
		assertThat(out.humanInput()).isFalse();
		assertThat(out.heartbeatIntervalSeconds()).isEqualTo(30);
		assertThat(out.binaryEncoding()).containsExactly("base64");
		assertThat(out.extensions()).containsExactly("arcpx.x.y.v1");
	}

	@Test
	void intersectWithNoneClearsLists() {
		Capabilities out = CapabilityNegotiator.intersect(Capabilities.reference(), Capabilities.NONE);
		assertThat(out.binaryEncoding()).isEmpty();
		assertThat(out.extensions()).isEmpty();
		assertThat(out.heartbeatRecovery()).isNull();
	}
}
