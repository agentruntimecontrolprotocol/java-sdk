package dev.arcp.runtime.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class ModelUseEnforcementTest {
    @Test
    void modelUseAuthorizesWithGlobPattern() throws Exception {
        Lease lease = Lease.builder().allow("model.use", "tier-fast/*").build();
        LeaseGuard guard = new LeaseGuard(lease, LeaseConstraints.none(), Clock.systemUTC());

        guard.authorizeModel("tier-fast/sonnet");
    }

    @Test
    void modelUseRejectsMiss() {
        Lease lease = Lease.builder().allow("model.use", "tier-slow/*").build();
        LeaseGuard guard = new LeaseGuard(lease, LeaseConstraints.none(), Clock.systemUTC());

        assertThatThrownBy(() -> guard.authorizeModel("tier-fast/sonnet"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void leaseContainsRejectsExpandedModelSet() {
        Lease parent = Lease.builder().allow("model.use", "tier-fast/*").build();
        Lease child = Lease.builder().allow("model.use", "tier-fast/sonnet").build();
        Lease expanded = Lease.builder().allow("model.use", "tier-slow/opus").build();

        assertThat(parent.contains(child)).isTrue();
        assertThat(parent.contains(expanded)).isFalse();
    }
}
