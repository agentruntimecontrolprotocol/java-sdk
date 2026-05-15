package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.lease.LeaseGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LeaseExpiryTest {

    @Test
    void leaseExpiredThrowsAtAuthorize() {
        Instant now = Instant.parse("2026-05-15T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Lease lease = Lease.builder().allow("fs.read", "**").build();
        LeaseConstraints constraints = LeaseConstraints.of(now.minusSeconds(1));
        LeaseGuard guard = new LeaseGuard(lease, constraints, clock);
        assertThatThrownBy(() -> guard.authorize("fs.read", "/etc/hosts"))
                .isInstanceOf(LeaseExpiredException.class);
    }

    @Test
    void unmatchedPatternDenied() {
        Clock clock = Clock.systemUTC();
        Lease lease = Lease.builder().allow("fs.read", "/workspace/**").build();
        LeaseGuard guard = new LeaseGuard(lease, LeaseConstraints.none(), clock);
        assertThatThrownBy(() -> guard.authorize("fs.read", "/etc/hosts"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void matchingDoubleStarAuthorizes() throws Exception {
        Clock clock = Clock.systemUTC();
        Lease lease = Lease.builder().allow("fs.write", "/workspace/myapp/**").build();
        LeaseGuard guard = new LeaseGuard(lease, LeaseConstraints.none(), clock);
        guard.authorize("fs.write", "/workspace/myapp/src/foo.java");
    }

    @Test
    void leaseConstraintsRejectsNonZSuffix() {
        assertThatThrownBy(() -> LeaseConstraints.parseStrictUtc("2026-05-15T00:00:00+00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containsEnforcesSubset() {
        Lease parent = Lease.builder()
                .allow("fs.read", "/workspace/**")
                .allow("net.fetch", "https://api.example.com/**")
                .build();
        Lease child = Lease.builder()
                .allow("fs.read", "/workspace/**")
                .build();
        assertThat(parent.contains(child)).isTrue();
        Lease bad = Lease.builder()
                .allow("fs.read", "/workspace/**")
                .allow("tool.call", "shell")
                .build();
        assertThat(parent.contains(bad)).isFalse();
    }
}
