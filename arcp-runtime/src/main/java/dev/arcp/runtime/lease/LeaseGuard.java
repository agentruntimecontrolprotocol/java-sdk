package dev.arcp.runtime.lease;

import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.error.PermissionDeniedException;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * §9.3 / §9.5 enforcement against a static lease + constraints. Glob matching uses a minimal {@code
 * *}/{@code **} subset that satisfies the spec examples.
 */
public final class LeaseGuard {

  private final Lease lease;
  private final LeaseConstraints constraints;
  private final Clock clock;
  private final ConcurrentHashMap<String, Pattern> compiledGlobs = new ConcurrentHashMap<>();

  /**
   * Creates a guard for one job's granted lease.
   *
   * @param lease the granted lease whose patterns authorize operations (§9.2)
   * @param constraints the lease constraints, including any {@code expires_at} (§9.5)
   * @param clock the clock used for expiry decisions
   */
  public LeaseGuard(Lease lease, LeaseConstraints constraints, Clock clock) {
    this.lease = lease;
    this.constraints = constraints;
    this.clock = clock;
  }

  /**
   * Returns the lease this guard enforces.
   *
   * @return the granted lease
   */
  public Lease lease() {
    return lease;
  }

  /**
   * Returns the constraints attached to the lease.
   *
   * @return the lease constraints
   */
  public LeaseConstraints constraints() {
    return constraints;
  }

  /**
   * Authorizes an operation against the lease: checks {@code expires_at} first (§9.5), then
   * requires at least one pattern in {@code namespace} to glob-match {@code pattern} (§9.3).
   *
   * @param namespace the capability namespace, e.g. {@code fs.read} or {@code model.use}
   * @param pattern the concrete value being attempted
   * @throws PermissionDeniedException if no lease pattern in {@code namespace} permits the value
   * @throws LeaseExpiredException if the lease's {@code expires_at} has passed
   */
  public void authorize(String namespace, String pattern)
      throws PermissionDeniedException, LeaseExpiredException {
    if (constraints.expiresAt() != null && !clock.instant().isBefore(constraints.expiresAt())) {
      throw new LeaseExpiredException(
          "lease expired at " + constraints.expiresAt() + " for " + namespace);
    }
    List<String> patterns = lease.patterns(namespace);
    if (patterns.stream().noneMatch(allowed -> matchesCached(allowed, pattern))) {
      throw new PermissionDeniedException(
          namespace + " does not permit " + pattern + "; allowed=" + patterns);
    }
  }

  /**
   * Authorizes use of a model against the {@code model.use} capability (§9.7).
   *
   * @param modelId the model identifier being requested
   * @throws PermissionDeniedException if no {@code model.use} pattern permits {@code modelId}
   * @throws LeaseExpiredException if the lease's {@code expires_at} has passed
   */
  public void authorizeModel(String modelId)
      throws PermissionDeniedException, LeaseExpiredException {
    authorize("model.use", modelId);
  }

  private boolean matchesCached(String pattern, String value) {
    return compiledGlobs.computeIfAbsent(pattern, LeaseGuard::globToRegex).matcher(value).matches();
  }

  static boolean matches(String pattern, String value) {
    return globToRegex(pattern).matcher(value).matches();
  }

  static java.util.regex.Pattern globToRegex(String glob) {
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

  /**
   * Builds the {@link LeaseExpiredException} for an expired lease without throwing it, so callers
   * can route it through their own error path (§9.5).
   *
   * @param constraints the lease constraints carrying the optional {@code expires_at}
   * @param clock the clock used for the expiry decision
   * @return the exception to surface, or {@code null} if no expiry is set or it has not passed
   */
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
