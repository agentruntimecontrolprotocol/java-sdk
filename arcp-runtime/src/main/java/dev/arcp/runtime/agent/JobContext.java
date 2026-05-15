package dev.arcp.runtime.agent;

import dev.arcp.core.events.EventBody;
import dev.arcp.core.error.BudgetExhaustedException;
import dev.arcp.core.error.LeaseExpiredException;
import dev.arcp.core.error.PermissionDeniedException;

/** Per-job runtime handle passed to {@link Agent#run}. */
public interface JobContext {

    /** Emit a job event for this job. Thread-safe; ordering preserved per job. */
    void emit(EventBody body);

    /** {@code true} if a {@code job.cancel} or session close has been observed. */
    boolean cancelled();

    /**
     * Authorize an operation against the active lease (§9.3 / §9.5 / §9.6).
     * Throws on lease subset violation, expired lease, or exhausted budget.
     */
    void authorize(String namespace, String pattern)
            throws PermissionDeniedException, LeaseExpiredException, BudgetExhaustedException;
}
