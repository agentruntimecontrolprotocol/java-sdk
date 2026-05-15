package dev.arcp.core.error;

public final class BudgetExhaustedException extends NonRetryableArcpException {
    public BudgetExhaustedException(String message) {
        super(ErrorCode.BUDGET_EXHAUSTED, message);
    }
}
