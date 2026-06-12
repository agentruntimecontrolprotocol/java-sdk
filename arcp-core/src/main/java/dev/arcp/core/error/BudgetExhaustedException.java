package dev.arcp.core.error;

/**
 * §12 {@code BUDGET_EXHAUSTED}: a {@code cost.budget} counter reached zero (§9.6). Never retryable
 * — a naive retry fails identically.
 */
public class BudgetExhaustedException extends NonRetryableArcpException {
  /**
   * Creates the exception.
   *
   * @param message human-readable detail
   */
  public BudgetExhaustedException(String message) {
    super(ErrorCode.BUDGET_EXHAUSTED, message);
  }
}
