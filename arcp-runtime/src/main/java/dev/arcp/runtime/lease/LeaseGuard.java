package dev.arcp.runtime.lease;

import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * §9.3 / §9.5 enforcement against a static lease + constraints. Glob matching
 * uses a minimal {@code *}/{@code **} subset that satisfies the spec examples.
 */
public final class LeaseGuard {

    private final Lease lease;
    private final LeaseConstraints constraints;
    private final Clock clock;

    public LeaseGuard(Lease lease, LeaseConstraints constraints, Clock clock) {
        this.lease = lease;
        this.constraints = constraints;
        this.clock = clock;
    }

    public Lease lease() {
        return lease;
    }

    public LeaseConstraints constraints() {
        return constraints;
    }

    public void authorize(String namespace, String pattern)
            throws PermissionDeniedException, LeaseExpiredException {
        if (constraints.expiresAt() != null && !clock.instant().isBefore(constraints.expiresAt())) {
            throw new LeaseExpiredException(
                    "lease expired at " + constraints.expiresAt() + " for " + namespace);
        }
        List<String> patterns = lease.patterns(namespace);
        for (String allowed : patterns) {
            if (matches(allowed, pattern)) {
                return;
            }
        }
        throw new PermissionDeniedException(
                namespace + " does not permit " + pattern + "; allowed=" + patterns);
    }

    static boolean matches(String pattern, String value) {
        return globToRegex(pattern).matcher(value).matches();
    }

    private static java.util.regex.Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                    continue;
                }
                sb.append("[^/]*");
            } else if ("\\.+()[]{}^$|?".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
            i++;
        }
        sb.append('$');
        return java.util.regex.Pattern.compile(sb.toString());
    }

    public static @Nullable LeaseExpiredException expiredOrNull(
            LeaseConstraints constraints, Clock clock) {
        if (constraints.expiresAt() == null) {
            return null;
        }
        if (!clock.instant().isBefore(constraints.expiresAt())) {
            return new LeaseExpiredException("lease expired at " + constraints.expiresAt());
        }
        return null;
    }
}
