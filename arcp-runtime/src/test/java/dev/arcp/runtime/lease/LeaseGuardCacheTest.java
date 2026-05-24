package dev.arcp.runtime.lease;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeaseGuardCacheTest {

  @Test
  void repeatedAuthorize_reusesCachedPattern() {
    Lease lease = new Lease(Map.of("file.read", List.of("/etc/**", "/var/log/*.log")));
    LeaseGuard guard = new LeaseGuard(lease, LeaseConstraints.none(), Clock.systemUTC());
    for (int i = 0; i < 1000; i++) {
      assertDoesNotThrow(() -> guard.authorize("file.read", "/etc/passwd"));
    }
    assertThrows(
        PermissionDeniedException.class,
        () -> guard.authorize("file.read", "/home/user/secret.txt"));
  }
}
