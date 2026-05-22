package dev.arcp.core.error;

import org.jspecify.annotations.Nullable;

public final class UpstreamBudgetExhaustedException extends BudgetExhaustedException {
  private final @Nullable String upstreamResponseBody;

  public UpstreamBudgetExhaustedException(String message, @Nullable String upstreamResponseBody) {
    super(message);
    this.upstreamResponseBody = upstreamResponseBody;
  }

  public @Nullable String upstreamResponseBody() {
    return upstreamResponseBody;
  }
}
