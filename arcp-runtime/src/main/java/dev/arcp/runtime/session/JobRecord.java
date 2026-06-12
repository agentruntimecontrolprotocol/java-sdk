package dev.arcp.runtime.session;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.runtime.credentials.IssuedCredential;
import dev.arcp.runtime.lease.BudgetCounters;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
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

  /** Default per-job event-history cap when none is supplied (e.g. in unit tests). */
  public static final int DEFAULT_HISTORY_CAPACITY = 1024;

  /** A recorded event retained for §7.6 subscribe-history replay. */
  public record RecordedEvent(long producerSeq, JobEvent event) {}

  private final JobId jobId;
  private final String resolvedAgent;
  private final Principal principal;
  private final Lease lease;
  private final LeaseConstraints constraints;
  private final BudgetCounters budget;
  private final Instant createdAt;
  private final @Nullable TraceId traceId;
  private final int historyCapacity;
  private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
  private final AtomicReference<@Nullable Long> lastEventSeq = new AtomicReference<>(null);
  // Bounded ring of recent events (§7.6: replay is bounded by the resume buffer window). Guarded by
  // its own monitor; appends are O(1) and the buffer never grows past historyCapacity.
  private final Deque<RecordedEvent> eventHistory = new ArrayDeque<>();
  private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
  private volatile @Nullable Future<?> worker;
  private volatile @Nullable ScheduledFuture<?> expiryWatchdog;
  private volatile @Nullable ScheduledFuture<?> maxRuntimeWatchdog;
  private volatile @Nullable Map<String, BigDecimal> acceptedBudget;
  private final Object credentialsLock = new Object();
  private final ArrayList<IssuedCredential> credentials = new ArrayList<>();

  public JobRecord(
      JobId jobId,
      String resolvedAgent,
      Principal principal,
      Lease lease,
      LeaseConstraints constraints,
      BudgetCounters budget,
      Instant createdAt,
      @Nullable TraceId traceId) {
    this(
        jobId,
        resolvedAgent,
        principal,
        lease,
        constraints,
        budget,
        createdAt,
        traceId,
        DEFAULT_HISTORY_CAPACITY);
  }

  public JobRecord(
      JobId jobId,
      String resolvedAgent,
      Principal principal,
      Lease lease,
      LeaseConstraints constraints,
      BudgetCounters budget,
      Instant createdAt,
      @Nullable TraceId traceId,
      int historyCapacity) {
    this.jobId = jobId;
    this.resolvedAgent = resolvedAgent;
    this.principal = principal;
    this.lease = lease;
    this.constraints = constraints;
    this.budget = budget;
    this.createdAt = createdAt;
    this.traceId = traceId;
    this.historyCapacity = historyCapacity > 0 ? historyCapacity : DEFAULT_HISTORY_CAPACITY;
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

  public void setMaxRuntimeWatchdog(ScheduledFuture<?> watchdog) {
    this.maxRuntimeWatchdog = watchdog;
  }

  public @Nullable ScheduledFuture<?> maxRuntimeWatchdog() {
    return maxRuntimeWatchdog;
  }

  /** Snapshot of the budget map returned in the original {@code job.accepted} (§7.2 replay). */
  public void setAcceptedBudget(Map<String, BigDecimal> snapshot) {
    this.acceptedBudget = Map.copyOf(snapshot);
  }

  public @Nullable Map<String, BigDecimal> acceptedBudget() {
    return acceptedBudget;
  }

  public void setLastEventSeq(long seq) {
    lastEventSeq.set(seq);
  }

  public @Nullable Long lastEventSeq() {
    return lastEventSeq.get();
  }

  public void recordEvent(long producerSeq, JobEvent event) {
    synchronized (eventHistory) {
      if (eventHistory.size() == historyCapacity) {
        eventHistory.removeFirst();
      }
      eventHistory.addLast(new RecordedEvent(producerSeq, event));
    }
  }

  public List<RecordedEvent> eventsSince(long fromSeq) {
    synchronized (eventHistory) {
      return eventHistory.stream().filter(e -> e.producerSeq() > fromSeq).toList();
    }
  }

  public int eventHistorySize() {
    synchronized (eventHistory) {
      return eventHistory.size();
    }
  }

  public List<Subscriber> subscribers() {
    return Collections.unmodifiableList(subscribers);
  }

  public void addSubscriber(Subscriber subscriber) {
    subscribers.add(subscriber);
  }

  public boolean removeSubscribersWhere(Predicate<Subscriber> predicate) {
    return subscribers.removeIf(predicate);
  }

  public List<IssuedCredential> credentials() {
    synchronized (credentialsLock) {
      return List.copyOf(credentials);
    }
  }

  public void setCredentials(List<IssuedCredential> issued) {
    synchronized (credentialsLock) {
      credentials.clear();
      credentials.addAll(issued);
    }
  }

  public @Nullable IssuedCredential replaceCredential(CredentialId id, IssuedCredential next) {
    synchronized (credentialsLock) {
      for (int i = 0; i < credentials.size(); i++) {
        IssuedCredential current = credentials.get(i);
        if (current.wire().id().equals(id)) {
          IssuedCredential prior = current;
          credentials.set(i, next);
          return prior;
        }
      }
      credentials.add(next);
      return null;
    }
  }

  public List<IssuedCredential> drainCredentials() {
    synchronized (credentialsLock) {
      List<IssuedCredential> drained = new ArrayList<>(credentials);
      credentials.clear();
      return drained;
    }
  }

  public record Subscriber(SessionLoop session, JobId jobId) {}
}
