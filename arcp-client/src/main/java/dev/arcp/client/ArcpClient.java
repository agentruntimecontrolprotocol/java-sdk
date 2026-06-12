package dev.arcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.error.ErrorPayload;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.Events;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobCancel;
import dev.arcp.core.messages.JobCancelled;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.JobSubscribe;
import dev.arcp.core.messages.JobSubscribed;
import dev.arcp.core.messages.JobSummary;
import dev.arcp.core.messages.JobUnsubscribe;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.SessionAck;
import dev.arcp.core.messages.SessionBye;
import dev.arcp.core.messages.SessionClosed;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionJobs;
import dev.arcp.core.messages.SessionListJobs;
import dev.arcp.core.messages.SessionPing;
import dev.arcp.core.messages.SessionPong;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ARCP client over a {@link Transport}. Owns the transport for the lifetime of the session; closing
 * the client closes the transport.
 */
public final class ArcpClient implements AutoCloseable, Flow.Subscriber<Envelope> {

  private static final Logger log = LoggerFactory.getLogger(ArcpClient.class);

  static EnumSet<Feature> safeFeatureCopy(Set<Feature> features) {
    if (features == null || features.isEmpty()) {
      return EnumSet.noneOf(Feature.class);
    }
    return EnumSet.copyOf(features);
  }

  private final Transport transport;
  private final ObjectMapper mapper;
  private final ClientInfo info;
  private final Auth auth;
  private final Set<Feature> requestedFeatures;
  private final CompletableFuture<Session> sessionFuture = new CompletableFuture<>();
  private final ConcurrentHashMap<JobId, Outstanding> outstanding = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<MessageId, CompletableFuture<SessionJobs>> listRequests =
      new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;
  private final boolean autoAck;
  private final Duration ackInterval;
  private final Duration submitTimeout;
  // Set while the transport's inbound delivery thread is inside dispatch(); used to fail fast if a
  // user completes a future on this thread and then calls blocking submit (#106).
  private final ThreadLocal<Boolean> inDispatch = ThreadLocal.withInitial(() -> Boolean.FALSE);
  private final AtomicLong lastSeenSeq = new AtomicLong(-1);
  private final AtomicLong lastAckedSeq = new AtomicLong(-1);
  private final AtomicLong lastInboundMillis = new AtomicLong(System.currentTimeMillis());
  private @Nullable ScheduledFuture<?> ackTick;
  private @Nullable ScheduledFuture<?> heartbeatWatchdog;
  private final ConcurrentHashMap<JobId, SubmissionPublisher<EventBody>> liveSubscribers =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<JobId, ExecutorService> liveExecutors = new ConcurrentHashMap<>();
  private final boolean ownedScheduler;

  // Reserved for future use by tests asserting FIFO insertion order of pending submits.
  @SuppressWarnings("unused")
  private final ConcurrentLinkedDeque<MessageId> pendingSubmitOrder = new ConcurrentLinkedDeque<>();

  @SuppressWarnings("unused")
  private Flow.@Nullable Subscription subscription;

  private volatile @Nullable SessionId sessionId;
  private volatile @Nullable Session session;
  private volatile boolean closed;
  private final @Nullable String resumeToken;
  private final @Nullable Long lastEventSeq;

  private ArcpClient(Builder b) {
    this.transport = Objects.requireNonNull(b.transport, "transport");
    this.mapper = b.mapper != null ? b.mapper : ArcpMapper.shared();
    this.info = b.info;
    this.auth = b.auth;
    this.requestedFeatures = safeFeatureCopy(b.features);
    this.autoAck = b.autoAck;
    this.ackInterval = b.ackInterval;
    this.submitTimeout = b.submitTimeout;
    if (b.scheduler != null) {
      this.scheduler = b.scheduler;
      this.ownedScheduler = false;
    } else {
      this.scheduler =
          Executors.newScheduledThreadPool(
              1,
              r -> Thread.ofPlatform().name("arcp-client-scheduler", 0).daemon(true).unstarted(r));
      this.ownedScheduler = true;
    }
    this.resumeToken = b.resumeToken;
    this.lastEventSeq = b.lastEventSeq;
  }

  public static Builder builder(Transport transport) {
    return new Builder(transport);
  }

  /** Send hello and return a future completing with the negotiated {@link Session}. */
  public CompletableFuture<Session> connect() {
    transport.incoming().subscribe(this);
    SessionHello hello =
        new SessionHello(info, auth, Capabilities.of(requestedFeatures), resumeToken, lastEventSeq);
    send(Message.Type.SESSION_HELLO, hello, null, null, null, null);
    return sessionFuture;
  }

  public Session connect(Duration timeout)
      throws InterruptedException, TimeoutException, ArcpException {
    try {
      return connect().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof ArcpException ax) {
        throw ax;
      }
      throw new IllegalStateException("connect failed", cause);
    }
  }

  public JobHandle submit(JobSubmit submit) {
    return submit(submit, null);
  }

  /**
   * Blocking submit. Returns once the runtime acknowledges with {@code job.accepted} (or fails on
   * rejection). Bounded by the configured submit timeout so it can never block forever (#106).
   *
   * <p>Must not be called from a dispatch/result callback (i.e. the transport inbound thread); doing
   * so would deadlock because the acknowledgement is delivered by that same thread. Such a call
   * fails fast with {@link IllegalStateException}.
   */
  public JobHandle submit(JobSubmit submit, @Nullable TraceId traceId) {
    if (Boolean.TRUE.equals(inDispatch.get())) {
      throw new IllegalStateException(
          "submit() must not be called from an event/result callback; use submitAsync()");
    }
    CompletableFuture<JobHandle> future = submitAsync(submit, traceId);
    try {
      return future.get(submitTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      pendingSubmits.removeIf(p -> p.outstanding().handleFuture == future);
      future.completeExceptionally(e);
      throw new IllegalStateException(
          "submit timed out after " + submitTimeout + " awaiting job.accepted", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("submit interrupted", e);
    } catch (java.util.concurrent.ExecutionException e) {
      // Preserve the prior join() behavior: surface the cause (e.g. an ArcpException such as
      // DuplicateKeyException) wrapped in an unchecked CompletionException so callers can inspect
      // getCause().
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new java.util.concurrent.CompletionException(cause);
    }
  }

  /** Non-blocking submit. Completes with the {@link JobHandle} on {@code job.accepted} (#106). */
  public CompletableFuture<JobHandle> submitAsync(JobSubmit submit) {
    return submitAsync(submit, null);
  }

  public CompletableFuture<JobHandle> submitAsync(JobSubmit submit, @Nullable TraceId traceId) {
    Outstanding o = new Outstanding();
    MessageId requestId = MessageId.generate();
    // The put-then-send pair must be atomic w.r.t. other submits so that the
    // FIFO order of pendingSubmits matches the wire order observed by the
    // runtime; that ordering is how handleAccepted correlates JobAccepted
    // back to the right pending submit (the runtime does not echo our
    // request id on job.accepted).
    submitLock.lock();
    try {
      pendingSubmits.add(new PendingSubmit(requestId, o));
      send(Message.Type.JOB_SUBMIT, submit, sessionId, traceId, null, null, requestId);
    } finally {
      submitLock.unlock();
    }
    return o.handleFuture;
  }

  public Page<JobSummary> listJobs(@Nullable JobFilter filter)
      throws InterruptedException, TimeoutException, ArcpException {
    return listJobs(filter, null, null);
  }

  /**
   * List jobs with optional pagination. Supply {@code cursor} from the previous {@link Page} to
   * continue, or {@code null} to fetch the first page. {@code limit} caps the page size.
   */
  public Page<JobSummary> listJobs(
      @Nullable JobFilter filter, @Nullable Integer limit, @Nullable String cursor)
      throws InterruptedException, TimeoutException, ArcpException {
    SessionListJobs req = new SessionListJobs(filter, limit, cursor);
    MessageId reqId = MessageId.generate();
    CompletableFuture<SessionJobs> fut = new CompletableFuture<>();
    listRequests.put(reqId, fut);
    send(Message.Type.SESSION_LIST_JOBS, req, sessionId, null, null, null, reqId);
    try {
      SessionJobs response = fut.get(10, TimeUnit.SECONDS);
      return new Page<>(response.jobs(), response.nextCursor());
    } catch (InterruptedException e) {
      listRequests.remove(reqId);
      Thread.currentThread().interrupt();
      throw e;
    } catch (TimeoutException e) {
      listRequests.remove(reqId);
      throw e;
    } catch (java.util.concurrent.ExecutionException e) {
      listRequests.remove(reqId);
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof ArcpException ax) {
        throw ax;
      }
      throw new IllegalStateException("list_jobs failed", cause);
    }
  }

  public Flow.Publisher<EventBody> subscribe(JobId jobId, SubscribeOptions options) {
    java.util.concurrent.atomic.AtomicBoolean inserted =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    SubmissionPublisher<EventBody> pub =
        liveSubscribers.computeIfAbsent(
            jobId,
            k -> {
              inserted.set(true);
              ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
              liveExecutors.put(jobId, exec);
              return new SubmissionPublisher<>(exec, 1024);
            });
    if (inserted.get()) {
      JobSubscribe sub =
          new JobSubscribe(
              jobId, options.history() ? options.fromEventSeq() : null, options.history());
      send(Message.Type.JOB_SUBSCRIBE, sub, sessionId, null, jobId, null);
    }
    return pub;
  }

  /**
   * Locally unsubscribe from job events and notify the runtime via {@code job.unsubscribe}. Closes
   * the local {@link Flow.Publisher} so any downstream subscribers see {@code onComplete}.
   */
  public void unsubscribe(JobId jobId) {
    SubmissionPublisher<EventBody> pub = liveSubscribers.remove(jobId);
    if (pub != null) {
      pub.close();
    }
    ExecutorService exec = liveExecutors.remove(jobId);
    if (exec != null) {
      exec.shutdown();
    }
    if (!closed) {
      try {
        send(Message.Type.JOB_UNSUBSCRIBE, new JobUnsubscribe(jobId), sessionId, null, jobId, null);
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
  }

  public void ack(long lastProcessedSeq) {
    send(Message.Type.SESSION_ACK, new SessionAck(lastProcessedSeq), sessionId, null, null, null);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    ScheduledFuture<?> ackF = ackTick;
    if (ackF != null) {
      ackF.cancel(false);
    }
    ScheduledFuture<?> hb = heartbeatWatchdog;
    if (hb != null) {
      hb.cancel(false);
    }
    try {
      send(Message.Type.SESSION_BYE, new SessionBye("client close"), sessionId, null, null, null);
    } catch (RuntimeException ignored) {
      // best-effort close
    }
    for (var pub : liveSubscribers.values()) {
      pub.close();
    }
    for (ExecutorService exec : liveExecutors.values()) {
      exec.shutdown();
    }
    liveExecutors.clear();
    try {
      transport.close();
    } catch (RuntimeException ignored) {
      // best-effort close
    }
    if (ownedScheduler) {
      scheduler.shutdownNow();
    }
  }

  /** Returns the highest event sequence number seen from the server, or -1 if none. */
  public long lastSeenSeq() {
    return lastSeenSeq.get();
  }

  /** Returns the active session after {@link #connect()} completes. */
  public Session session() {
    Session current = session;
    if (current == null) {
      throw new IllegalStateException("client is not connected");
    }
    return current;
  }

  @Override
  public void onSubscribe(Flow.Subscription s) {
    this.subscription = s;
    s.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(Envelope envelope) {
    lastInboundMillis.set(System.currentTimeMillis());
    Long seq = envelope.eventSeq();
    if (seq != null) {
      lastSeenSeq.updateAndGet(prev -> Math.max(prev, seq));
    }
    inDispatch.set(Boolean.TRUE);
    try {
      dispatch(envelope);
    } catch (RuntimeException e) {
      log.warn("client dispatch error for {}: {}", envelope.type(), e.toString());
    } finally {
      inDispatch.set(Boolean.FALSE);
    }
  }

  @Override
  public void onError(Throwable throwable) {
    failAll(throwable);
  }

  @Override
  public void onComplete() {
    if (!sessionFuture.isDone()) {
      failAll(new IllegalStateException("transport closed before welcome"));
    } else {
      // Transport closed after welcome — fail anything still in flight.
      failAll(new IllegalStateException("transport closed"));
    }
  }

  /**
   * Fail every outstanding future and complete every live subscription publisher exceptionally.
   * Called on transport error, unexpected transport close, and heartbeat-loss close so a caller
   * blocked on {@link #submit}, {@link #listJobs}, or {@link JobHandle#result()} does not wait
   * forever.
   */
  private void failAll(Throwable cause) {
    if (!sessionFuture.isDone()) {
      sessionFuture.completeExceptionally(cause);
    }
    for (PendingSubmit head; (head = pendingSubmits.pollFirst()) != null; ) {
      head.outstanding().handleFuture.completeExceptionally(cause);
    }
    for (java.util.Map.Entry<JobId, Outstanding> e : outstanding.entrySet()) {
      Outstanding o = e.getValue();
      if (!o.resultFuture.isDone()) {
        o.resultFuture.completeExceptionally(cause);
      }
      o.events.close();
    }
    outstanding.clear();
    for (java.util.Map.Entry<MessageId, CompletableFuture<SessionJobs>> e :
        listRequests.entrySet()) {
      if (!e.getValue().isDone()) {
        e.getValue().completeExceptionally(cause);
      }
    }
    listRequests.clear();
    for (SubmissionPublisher<EventBody> pub : liveSubscribers.values()) {
      pub.closeExceptionally(cause);
    }
    liveSubscribers.clear();
  }

  private void dispatch(Envelope envelope) {
    Message m;
    try {
      m = Messages.decode(mapper, envelope);
    } catch (RuntimeException e) {
      log.warn("client could not decode {}: {}", envelope.type(), e.getMessage());
      return;
    }
    switch (m) {
      case SessionWelcome welcome -> handleWelcome(envelope, welcome);
      case JobAccepted accepted -> handleAccepted(envelope, accepted);
      case JobEvent event -> handleJobEvent(envelope, event);
      case JobResult result -> handleResult(envelope, result);
      case JobError error -> handleError(envelope, error);
      case JobSubscribed ignored -> {
        /* signal */
      }
      case JobCancelled cancelled -> handleCancelled(envelope, cancelled);
      case SessionClosed ignored -> log.debug("session closed acknowledged by runtime");
      case SessionJobs jobs -> handleListResponse(jobs);
      case SessionPing ping -> handlePing(ping);
      case SessionPong ignored -> log.debug("client ignored: {}", envelope.type());
      case SessionAck ignored -> log.debug("client ignored: {}", envelope.type());
      case SessionHello ignored -> log.debug("client ignored: {}", envelope.type());
      case SessionBye ignored -> log.debug("client ignored: {}", envelope.type());
      case SessionListJobs ignored -> log.debug("client ignored: {}", envelope.type());
      case JobSubmit ignored -> log.debug("client ignored: {}", envelope.type());
      case JobCancel ignored -> log.debug("client ignored: {}", envelope.type());
      case JobSubscribe ignored -> log.debug("client ignored: {}", envelope.type());
      case JobUnsubscribe ignored -> log.debug("client ignored: {}", envelope.type());
    }
  }

  private void handleWelcome(Envelope envelope, SessionWelcome welcome) {
    this.sessionId = envelope.sessionId();
    Session s =
        new Session(
            envelope.sessionId(),
            welcome.capabilities().features(),
            welcome.resumeToken(),
            welcome.heartbeatIntervalSec() != null
                ? Duration.ofSeconds(welcome.heartbeatIntervalSec())
                : null,
            welcome.capabilities().agents() != null
                ? List.copyOf(welcome.capabilities().agents())
                : List.of());
    this.session = s;

    // §6.5 ack: periodic emit if negotiated and enabled.
    if (autoAck && s.negotiatedFeatures().contains(Feature.ACK)) {
      long periodMs = ackInterval.toMillis();
      ackTick =
          scheduler.scheduleAtFixedRate(this::maybeAck, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }
    // §6.4 heartbeat watchdog: detect two missed intervals.
    if (s.heartbeatInterval() != null && s.negotiatedFeatures().contains(Feature.HEARTBEAT)) {
      long intervalMs = s.heartbeatInterval().toMillis();
      heartbeatWatchdog =
          scheduler.scheduleAtFixedRate(
              () -> watchHeartbeat(intervalMs), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }
    sessionFuture.complete(s);
  }

  private void maybeAck() {
    long current = lastSeenSeq.get();
    long acked = lastAckedSeq.get();
    if (current > acked) {
      lastAckedSeq.set(current);
      try {
        send(Message.Type.SESSION_ACK, new SessionAck(current), sessionId, null, null, null);
      } catch (RuntimeException e) {
        log.warn("ack emit failed: {}", e.toString());
      }
    }
  }

  private void watchHeartbeat(long intervalMs) {
    long elapsed = System.currentTimeMillis() - lastInboundMillis.get();
    if (elapsed > intervalMs * 2) {
      log.info("client observed heartbeat loss; closing session");
      failAll(new IllegalStateException("heartbeat lost"));
      close();
    }
  }

  private record PendingSubmit(MessageId requestId, Outstanding outstanding) {}

  private final ConcurrentLinkedDeque<PendingSubmit> pendingSubmits = new ConcurrentLinkedDeque<>();
  private final java.util.concurrent.locks.ReentrantLock submitLock =
      new java.util.concurrent.locks.ReentrantLock();

  private void handleAccepted(Envelope envelope, JobAccepted accepted) {
    PendingSubmit head = pendingSubmits.pollFirst();
    if (head == null) {
      return;
    }
    // §9.8.1: drop credentials whose scheme this client does not recognize rather than failing the
    // whole acceptance (#97). Unknown schemes decode to CredentialScheme.UNKNOWN.
    JobAccepted visible = withRecognizedCredentials(accepted);
    Outstanding o = head.outstanding();
    o.jobId = visible.jobId();
    outstanding.put(visible.jobId(), o);
    o.handleFuture.complete(new ClientJobHandle(visible, o));
  }

  private static JobAccepted withRecognizedCredentials(JobAccepted accepted) {
    List<Credential> credentials = accepted.credentials();
    if (credentials == null || credentials.isEmpty()) {
      return accepted;
    }
    List<Credential> recognized =
        credentials.stream().filter(c -> c.scheme() != CredentialScheme.UNKNOWN).toList();
    if (recognized.size() == credentials.size()) {
      return accepted;
    }
    return new JobAccepted(
        accepted.jobId(),
        accepted.agent(),
        accepted.lease(),
        accepted.leaseConstraints(),
        accepted.budget(),
        recognized.isEmpty() ? null : recognized,
        accepted.acceptedAt(),
        accepted.traceId());
  }

  private void handleCancelled(Envelope envelope, JobCancelled cancelled) {
    // §7.4 ack: the terminal job.error CANCELLED that follows completes the result/subscriber; the
    // ack itself is informational.
    log.debug("job {} cancellation acknowledged: {}", envelope.jobId(), cancelled.reason());
  }

  private void handleJobEvent(Envelope envelope, JobEvent event) {
    JobId jid = envelope.jobId();
    if (jid == null) {
      return;
    }
    EventBody body = Events.decode(mapper, event.eventKind(), event.body());
    Outstanding o = outstanding.get(jid);
    if (o != null) {
      o.events.submit(body);
    }
    SubmissionPublisher<EventBody> sub = liveSubscribers.get(jid);
    if (sub != null) {
      sub.submit(body);
    }
  }

  private void handleResult(Envelope envelope, JobResult result) {
    JobId jid = envelope.jobId();
    if (jid == null) {
      return;
    }
    Outstanding o = outstanding.remove(jid);
    if (o != null) {
      o.events.close();
      o.resultFuture.complete(result);
    }
    // Complete the live subscriber publisher (if any) so "subscribe and iterate until complete"
    // consumers see onComplete instead of blocking forever, and per-job executors are released
    // (#105).
    completeLiveSubscriber(jid, null);
  }

  private void handleError(Envelope envelope, JobError err) {
    JobId jid = envelope.jobId();
    ArcpException ex = ArcpException.from(ErrorPayload.of(err.code(), err.message()));
    if (jid != null) {
      // Terminal error for a known job: fail its result and complete its subscriber. A jobful error
      // is never treated as a submit rejection (#102).
      Outstanding o = outstanding.remove(jid);
      if (o != null) {
        o.events.close();
        o.resultFuture.completeExceptionally(ex);
      }
      completeLiveSubscriber(jid, ex);
      return;
    }
    // Top-level (jobless) error: correlate to the originating request via the echoed envelope id so
    // a list_jobs/subscribe error never fails an unrelated in-flight submit (#102).
    MessageId correlationId = envelope.id();
    CompletableFuture<SessionJobs> listFuture = listRequests.remove(correlationId);
    if (listFuture != null) {
      listFuture.completeExceptionally(ex);
      return;
    }
    PendingSubmit match = removePendingSubmit(correlationId);
    if (match != null) {
      match.outstanding().handleFuture.completeExceptionally(ex);
      return;
    }
    log.warn("dropping uncorrelated top-level error {}: {}", err.code(), err.message());
  }

  private @Nullable PendingSubmit removePendingSubmit(MessageId requestId) {
    for (java.util.Iterator<PendingSubmit> it = pendingSubmits.iterator(); it.hasNext(); ) {
      PendingSubmit p = it.next();
      if (p.requestId().equals(requestId)) {
        it.remove();
        return p;
      }
    }
    return null;
  }

  private void completeLiveSubscriber(JobId jid, @Nullable Throwable error) {
    SubmissionPublisher<EventBody> pub = liveSubscribers.remove(jid);
    if (pub != null) {
      if (error != null) {
        pub.closeExceptionally(error);
      } else {
        pub.close();
      }
    }
    ExecutorService exec = liveExecutors.remove(jid);
    if (exec != null) {
      exec.shutdown();
    }
  }

  private void handleListResponse(SessionJobs jobs) {
    var fut = listRequests.remove(jobs.requestId());
    if (fut != null) {
      fut.complete(jobs);
    }
  }

  private void handlePing(SessionPing ping) {
    SessionPong pong = new SessionPong(ping.nonce(), java.time.Instant.now());
    send(Message.Type.SESSION_PONG, pong, sessionId, null, null, null);
  }

  private void send(
      Message.Type type,
      Message payload,
      @Nullable SessionId sid,
      @Nullable TraceId tid,
      @Nullable JobId jid,
      @Nullable Long seq) {
    send(type, payload, sid, tid, jid, seq, MessageId.generate());
  }

  private void send(
      Message.Type type,
      Message payload,
      @Nullable SessionId sid,
      @Nullable TraceId tid,
      @Nullable JobId jid,
      @Nullable Long seq,
      MessageId id) {
    ObjectNode payloadJson = Messages.encodePayload(mapper, payload);
    Envelope env = new Envelope(Envelope.VERSION, id, type.wire(), sid, tid, jid, seq, payloadJson);
    transport.send(env);
  }

  private final class Outstanding {
    final CompletableFuture<JobHandle> handleFuture = new CompletableFuture<>();
    final CompletableFuture<JobResult> resultFuture = new CompletableFuture<>();
    final ReplayingPublisher<EventBody> events = new ReplayingPublisher<>();
    @Nullable JobId jobId;
  }

  private final class ClientJobHandle implements JobHandle {
    private final JobAccepted accepted;
    private final Outstanding outstanding;

    ClientJobHandle(JobAccepted accepted, Outstanding o) {
      this.accepted = accepted;
      this.outstanding = o;
    }

    @Override
    public JobId jobId() {
      return accepted.jobId();
    }

    @Override
    public String resolvedAgent() {
      return accepted.agent();
    }

    @Override
    public JobAccepted accepted() {
      return accepted;
    }

    @Override
    public Flow.Publisher<EventBody> events() {
      return outstanding.events;
    }

    @Override
    public CompletableFuture<JobResult> result() {
      return outstanding.resultFuture;
    }

    @Override
    public void cancel() {
      send(
          Message.Type.JOB_CANCEL,
          new JobCancel("client cancel"),
          sessionId,
          null,
          accepted.jobId(),
          null);
    }
  }

  public static final class Builder {
    private final Transport transport;
    private @Nullable ObjectMapper mapper;
    private ClientInfo info = new ClientInfo("arcp-client-java", "1.0.0");
    private Auth auth = Auth.anonymous();
    private Set<Feature> features = EnumSet.allOf(Feature.class);
    private boolean autoAck = true;
    private Duration ackInterval = Duration.ofMillis(200);
    private Duration submitTimeout = Duration.ofSeconds(30);
    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable String resumeToken;
    private @Nullable Long lastEventSeq;

    Builder(Transport transport) {
      this.transport = transport;
    }

    public Builder mapper(ObjectMapper m) {
      this.mapper = m;
      return this;
    }

    public Builder client(String name, String version) {
      this.info = new ClientInfo(name, version);
      return this;
    }

    public Builder auth(Auth a) {
      this.auth = a;
      return this;
    }

    public Builder bearer(String token) {
      this.auth = Auth.bearer(token);
      return this;
    }

    public Builder features(Set<Feature> features) {
      this.features = safeFeatureCopy(features);
      return this;
    }

    public Builder autoAck(boolean enabled) {
      this.autoAck = enabled;
      return this;
    }

    public Builder ackInterval(Duration interval) {
      this.ackInterval = interval;
      return this;
    }

    /** Maximum time blocking {@link #submit(JobSubmit)} waits for {@code job.accepted} (#106). */
    public Builder submitTimeout(Duration timeout) {
      this.submitTimeout = timeout;
      return this;
    }

    public Builder scheduler(ScheduledExecutorService s) {
      this.scheduler = s;
      return this;
    }

    /** Resume a prior session by supplying the token received in {@link Session#resumeToken()}. */
    public Builder resumeToken(String token) {
      this.resumeToken = token;
      return this;
    }

    /**
     * Resume from a known event sequence number (§6.3). Used together with {@link
     * #resumeToken(String)} to re-subscribe to events the client may have missed.
     */
    public Builder lastEventSeq(long seq) {
      this.lastEventSeq = seq;
      return this;
    }

    public ArcpClient build() {
      return new ArcpClient(this);
    }
  }

  /** Construct a job submit payload conveniently. */
  public static JobSubmit jobSubmit(String agent, JsonNode input) {
    return new JobSubmit(AgentRef.parse(agent), input, null, null, null, null);
  }

  public static JobSubmit jobSubmit(
      String agent,
      JsonNode input,
      @Nullable Lease lease,
      @Nullable LeaseConstraints constraints,
      @Nullable String idempotencyKey,
      @Nullable Integer maxRuntimeSec) {
    if (constraints != null
        && constraints.expiresAt() != null
        && !constraints.expiresAt().isAfter(java.time.Instant.now())) {
      throw new IllegalArgumentException("expires_at must be in the future");
    }
    return new JobSubmit(
        AgentRef.parse(agent), input, lease, constraints, idempotencyKey, maxRuntimeSec);
  }
}
