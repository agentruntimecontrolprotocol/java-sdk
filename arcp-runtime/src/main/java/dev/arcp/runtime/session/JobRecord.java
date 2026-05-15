package dev.arcp.runtime.session;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.runtime.lease.BudgetCounters;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Bookkeeping for one in-flight job on the runtime side. */
public final class JobRecord {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCESS,
        ERROR,
        CANCELLED,
        TIMED_OUT;

        public boolean terminal() {
            return this != PENDING && this != RUNNING;
        }

        public String wire() {
            return switch (this) {
                case PENDING -> "pending";
                case RUNNING -> "running";
                case SUCCESS -> "success";
                case ERROR -> "error";
                case CANCELLED -> "cancelled";
                case TIMED_OUT -> "timed_out";
            };
        }
    }

    private final JobId jobId;
    private final String resolvedAgent;
    private final Principal principal;
    private final Lease lease;
    private final LeaseConstraints constraints;
    private final BudgetCounters budget;
    private final Instant createdAt;
    private final @Nullable TraceId traceId;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private final AtomicReference<@Nullable Long> lastEventSeq = new AtomicReference<>(null);
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile @Nullable Future<?> worker;
    private volatile @Nullable ScheduledFuture<?> expiryWatchdog;

    public JobRecord(
            JobId jobId,
            String resolvedAgent,
            Principal principal,
            Lease lease,
            LeaseConstraints constraints,
            BudgetCounters budget,
            Instant createdAt,
            @Nullable TraceId traceId) {
        this.jobId = jobId;
        this.resolvedAgent = resolvedAgent;
        this.principal = principal;
        this.lease = lease;
        this.constraints = constraints;
        this.budget = budget;
        this.createdAt = createdAt;
        this.traceId = traceId;
    }

    public JobId jobId() {
        return jobId;
    }

    public String resolvedAgent() {
        return resolvedAgent;
    }

    public Principal principal() {
        return principal;
    }

    public Lease lease() {
        return lease;
    }

    public LeaseConstraints constraints() {
        return constraints;
    }

    public BudgetCounters budget() {
        return budget;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public @Nullable TraceId traceId() {
        return traceId;
    }

    public Status status() {
        return status.get();
    }

    public boolean transitionTo(Status next) {
        Status prev;
        do {
            prev = status.get();
            if (prev.terminal()) {
                return false;
            }
        } while (!status.compareAndSet(prev, next));
        return true;
    }

    public void setWorker(Future<?> f) {
        this.worker = f;
    }

    public @Nullable Future<?> worker() {
        return worker;
    }

    public void setExpiryWatchdog(ScheduledFuture<?> watchdog) {
        this.expiryWatchdog = watchdog;
    }

    public @Nullable ScheduledFuture<?> expiryWatchdog() {
        return expiryWatchdog;
    }

    public void setLastEventSeq(long seq) {
        lastEventSeq.set(seq);
    }

    public @Nullable Long lastEventSeq() {
        return lastEventSeq.get();
    }

    public CopyOnWriteArrayList<Subscriber> subscribers() {
        return subscribers;
    }

    public record Subscriber(SessionLoop session, JobId jobId) {}
}
