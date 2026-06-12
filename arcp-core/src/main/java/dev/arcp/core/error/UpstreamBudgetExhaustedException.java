package dev.arcp.core.error;

import org.jspecify.annotations.Nullable;

/**
 * Budget exhaustion reported by an upstream enforcement boundary, such as a §9.8 provisioned
 * credential's gateway, rather than by the runtime's own §9.6 accounting. Carries the upstream
 * response body for diagnostics.
 */
public final class UpstreamBudgetExhaustedException extends BudgetExhaustedException {
  /** Raw response body returned by the upstream, or {@code null} if unavailable. */
  private final @Nullable String upstreamResponseBody;

  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   * @param upstreamResponseBody raw upstream response body, or {@code null} if unavailable
   */
  public UpstreamBudgetExhaustedException(String message, @Nullable String upstreamResponseBody) {
    super(message);
    this.upstreamResponseBody = upstreamResponseBody;
  }

  /**
   * Returns the raw response body returned by the upstream.
   *
   * @return the upstream response body, or {@code null} if unavailable
   */
  public @Nullable String upstreamResponseBody() {
    return upstreamResponseBody;
  }
}
