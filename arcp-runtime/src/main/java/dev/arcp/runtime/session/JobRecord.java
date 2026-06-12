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

  /** §7.3 job lifecycle states as tracked by the runtime. */
  public enum Status {
    /** Accepted but not yet running. */
    PENDING,
    /** Currently executing on the worker pool. */
    RUNNING,
    /** Terminal: completed and produced a {@code job.result}. */
    SUCCESS,
    /** Terminal: failed with a {@code job.error} (§12). */
    ERROR,
    /** Terminal: cancelled via {@code job.cancel} or session teardown (§7.4). */
    CANCELLED,
    /** Terminal: exceeded {@code max_runtime_sec}. */
    TIMED_OUT;

    /**
     * Tests whether this status is terminal per §7.3.
     *
     * @return {@code true} unless the status is {@link #PENDING} or {@link #RUNNING}
     */
    public boolean terminal() {
      return this != PENDING && this != RUNNING;
    }

    /**
     * Returns the lowercase wire form used in listings and {@code final_status} (§7.3).
     *
     * @return the wire representation, e.g. {@code timed_out}
     */
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

  /**
   * A recorded event retained for §7.6 subscribe-history replay.
   *
   * @param producerSeq the job-scoped, monotonically increasing production sequence number
   * @param event the event as originally emitted
   */
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

  /**
   * Creates a record with the {@linkplain #DEFAULT_HISTORY_CAPACITY default} event-history cap.
   *
   * @param jobId the id assigned at acceptance (§7.1)
   * @param resolvedAgent the pinned {@code name@version} the job runs as (§7.5)
   * @param principal the authenticated submitter
   * @param lease the granted lease (§9)
   * @param constraints the lease constraints, including any {@code expires_at} (§9.5)
   * @param budget the per-currency budget counters (§9.6)
   * @param createdAt the acceptance timestamp
   * @param traceId the propagated trace id, or {@code null} if the client sent none (§11)
   */
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

  /**
   * Creates a record with an explicit event-history cap.
   *
   * @param jobId the id assigned at acceptance (§7.1)
   * @param resolvedAgent the pinned {@code name@version} the job runs as (§7.5)
   * @param principal the authenticated submitter
   * @param lease the granted lease (§9)
   * @param constraints the lease constraints, including any {@code expires_at} (§9.5)
   * @param budget the per-currency budget counters (§9.6)
   * @param createdAt the acceptance timestamp
   * @param traceId the propagated trace id, or {@code null} if the client sent none (§11)
   * @param historyCapacity the maximum events retained for §7.6 replay; non-positive values fall
   *     back to {@link #DEFAULT_HISTORY_CAPACITY}
   */
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

  /**
   * Returns the job's id (§7.1).
   *
   * @return the job id
   */
  public JobId jobId() {
    return jobId;
  }

  /**
   * Returns the pinned {@code name@version} the job runs as (§7.5).
   *
   * @return the resolved agent in wire form
   */
  public String resolvedAgent() {
    return resolvedAgent;
  }

  /**
   * Returns the authenticated submitter; listing and subscription are scoped to it (§6.6).
   *
   * @return the owning principal
   */
  public Principal principal() {
    return principal;
  }

  /**
   * Returns the lease the job runs under (§9).
   *
   * @return the granted lease
   */
  public Lease lease() {
    return lease;
  }

  /**
   * Returns the constraints attached to the lease (§9.5).
   *
   * @return the lease constraints
   */
  public LeaseConstraints constraints() {
    return constraints;
  }

  /**
   * Returns the live per-currency budget counters (§9.6).
   *
   * @return the budget counters
   */
  public BudgetCounters budget() {
    return budget;
  }

  /**
   * Returns when the job was accepted.
   *
   * @return the acceptance timestamp
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * Returns the trace id propagated from {@code job.submit} (§11).
   *
   * @return the trace id, or {@code null} if the client sent none
   */
  public @Nullable TraceId traceId() {
    return traceId;
  }

  /**
   * Returns the job's current lifecycle status (§7.3).
   *
   * @return the current status
   */
  public Status status() {
    return status.get();
  }

  /**
   * Atomically moves the job to {@code next} unless it already reached a terminal state, so the
   * first terminal transition wins (§7.3).
   *
   * @param next the status to transition to
   * @return {@code true} if the transition was applied
   */
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

  /**
   * Attaches the worker-pool future executing this job, used to interrupt it on cancellation.
   *
   * @param f the running worker future
   */
  public void setWorker(Future<?> f) {
    this.worker = f;
  }

  /**
   * Returns the worker-pool future executing this job.
   *
   * @return the worker future, or {@code null} before the job starts running
   */
  public @Nullable Future<?> worker() {
    return worker;
  }

  /**
   * Attaches the watchdog that fails the job when its lease's {@code expires_at} passes (§9.5).
   *
   * @param watchdog the scheduled lease-expiry task
   */
  public void setExpiryWatchdog(ScheduledFuture<?> watchdog) {
    this.expiryWatchdog = watchdog;
  }

  /**
   * Returns the lease-expiry watchdog (§9.5).
   *
   * @return the scheduled task, or {@code null} if the lease has no {@code expires_at}
   */
  public @Nullable ScheduledFuture<?> expiryWatchdog() {
    return expiryWatchdog;
  }

  /**
   * Attaches the watchdog that times the job out when {@code max_runtime_sec} elapses (§7.1).
   *
   * @param watchdog the scheduled max-runtime task
   */
  public void setMaxRuntimeWatchdog(ScheduledFuture<?> watchdog) {
    this.maxRuntimeWatchdog = watchdog;
  }

  /**
   * Returns the max-runtime watchdog (§7.1).
   *
   * @return the scheduled task, or {@code null} if no {@code max_runtime_sec} was requested
   */
  public @Nullable ScheduledFuture<?> maxRuntimeWatchdog() {
    return maxRuntimeWatchdog;
  }

  /**
   * Stores the budget map returned in the original {@code job.accepted}, replayed verbatim for an
   * idempotent resubmission (§7.2).
   *
   * @param snapshot the per-currency budget echoed at acceptance; copied defensively
   */
  public void setAcceptedBudget(Map<String, BigDecimal> snapshot) {
    this.acceptedBudget = Map.copyOf(snapshot);
  }

  /**
   * Returns the budget map from the original {@code job.accepted} (§7.2 replay).
   *
   * @return the acceptance-time budget snapshot, or {@code null} if none was recorded
   */
  public @Nullable Map<String, BigDecimal> acceptedBudget() {
    return acceptedBudget;
  }

  /**
   * Records the {@code event_seq} of the most recent event sent for this job (§8.3).
   *
   * @param seq the session-scoped event sequence number
   */
  public void setLastEventSeq(long seq) {
    lastEventSeq.set(seq);
  }

  /**
   * Returns the {@code event_seq} of the most recent event sent for this job, surfaced as {@code
   * last_event_seq} in §6.6 listings.
   *
   * @return the last event sequence, or {@code null} if no event has been sent yet
   */
  public @Nullable Long lastEventSeq() {
    return lastEventSeq.get();
  }

  /**
   * Appends an event to the bounded history kept for §7.6 subscribe replay, evicting the oldest
   * entry when the cap is reached.
   *
   * @param producerSeq the job-scoped production sequence of the event
   * @param event the event to retain
   */
  public void recordEvent(long producerSeq, JobEvent event) {
    synchronized (eventHistory) {
      if (eventHistory.size() == historyCapacity) {
        eventHistory.removeFirst();
      }
      eventHistory.addLast(new RecordedEvent(producerSeq, event));
    }
  }

  /**
   * Returns retained events for §7.6 {@code from_seq} replay.
   *
   * @param fromSeq the producer sequence to replay after
   * @return recorded events with {@code producerSeq} greater than {@code fromSeq}, in order
   */
  public List<RecordedEvent> eventsSince(long fromSeq) {
    synchronized (eventHistory) {
      return eventHistory.stream().filter(e -> e.producerSeq() > fromSeq).toList();
    }
  }

  /**
   * Returns the number of events currently retained for replay.
   *
   * @return the event-history size
   */
  public int eventHistorySize() {
    synchronized (eventHistory) {
      return eventHistory.size();
    }
  }

  /**
   * Returns the sessions subscribed to this job's events (§7.6).
   *
   * @return an unmodifiable view of the current subscribers
   */
  public List<Subscriber> subscribers() {
    return Collections.unmodifiableList(subscribers);
  }

  /**
   * Adds a session subscription established via {@code job.subscribe} (§7.6).
   *
   * @param subscriber the subscribing session and the job id it subscribed under
   */
  public void addSubscriber(Subscriber subscriber) {
    subscribers.add(subscriber);
  }

  /**
   * Removes every subscriber matching {@code predicate}, e.g. all subscriptions of a closing
   * session.
   *
   * @param predicate selects the subscribers to remove
   * @return {@code true} if at least one subscriber was removed
   */
  public boolean removeSubscribersWhere(Predicate<Subscriber> predicate) {
    return subscribers.removeIf(predicate);
  }

  /**
   * Returns the provisioned credentials currently attached to this job (§9.8).
   *
   * @return an immutable snapshot of the attached credentials
   */
  public List<IssuedCredential> credentials() {
    synchronized (credentialsLock) {
      return List.copyOf(credentials);
    }
  }

  /**
   * Replaces the attached credentials with those issued at acceptance (§9.8.2).
   *
   * @param issued the credentials now live for this job
   */
  public void setCredentials(List<IssuedCredential> issued) {
    synchronized (credentialsLock) {
      credentials.clear();
      credentials.addAll(issued);
    }
  }

  /**
   * Swaps credential {@code id} for {@code next} during a §9.8.2 rotation, appending {@code next}
   * if no credential with that id is attached.
   *
   * @param id the id of the credential being rotated
   * @param next the replacement credential
   * @return the credential previously held under {@code id}, or {@code null} if none matched
   */
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

  /**
   * Detaches and returns all credentials so they can be revoked exactly once at job termination
   * (§9.8.2).
   *
   * @return the credentials that were attached; subsequent calls return an empty list
   */
  public List<IssuedCredential> drainCredentials() {
    synchronized (credentialsLock) {
      List<IssuedCredential> drained = new ArrayList<>(credentials);
      credentials.clear();
      return drained;
    }
  }

  /**
   * One session's subscription to this job's events (§7.6).
   *
   * @param session the session receiving fanned-out events
   * @param jobId the job id the subscription was established under
   */
  public record Subscriber(SessionLoop session, JobId jobId) {}
}
