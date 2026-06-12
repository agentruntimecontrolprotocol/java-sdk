package dev.arcp.runtime.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.error.UpstreamBudgetExhaustedException;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
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
import dev.arcp.core.messages.JobUnsubscribe;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.RuntimeInfo;
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
import dev.arcp.core.wire.Envelope;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.Agent;
import dev.arcp.runtime.agent.AgentRegistry;
import dev.arcp.runtime.agent.JobContext;
import dev.arcp.runtime.agent.JobInput;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.credentials.CredentialBinding;
import dev.arcp.runtime.credentials.IssuedCredential;
import dev.arcp.runtime.heartbeat.HeartbeatTracker;
import dev.arcp.runtime.idempotency.IdempotencyFingerprint;
import dev.arcp.runtime.lease.BudgetCounters;
import dev.arcp.runtime.lease.LeaseGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-transport runtime session: handshake, message dispatch, job lifecycle, heartbeats, lease
 * enforcement, idempotency, and subscribe fan-out.
 */
public final class SessionLoop implements Flow.Subscriber<Envelope> {

  private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

  public enum Phase {
    AWAITING_HELLO,
    ACTIVE,
    PARKED,
    CLOSED
  }

  private final ArcpRuntime runtime;
  // Swappable so a resume can reattach the same session identity (and its in-flight jobs) to a new
  // transport (§6.3, #22).
  private volatile Transport transport;
  private final ObjectMapper mapper;
  private final AgentRegistry agents;
  private final String pendingId = "pending:" + UUID.randomUUID();

  private final java.util.concurrent.atomic.AtomicReference<Phase> phase =
      new java.util.concurrent.atomic.AtomicReference<>(Phase.AWAITING_HELLO);
  private volatile @Nullable SessionId sessionId;
  private volatile @Nullable Principal principal;
  private volatile Set<Feature> negotiated = EnumSet.noneOf(Feature.class);
  private volatile @Nullable String resumeToken;

  private final AtomicLong eventSeq = new AtomicLong(0);
  private final AtomicLong lastProcessedSeq = new AtomicLong(-1);
  private final ResumeBuffer resumeBuffer;
  private final HeartbeatTracker heartbeat;
  private final CredentialBinding credentialBinding;
  private volatile @Nullable ScheduledFuture<?> heartbeatTick;
  private volatile @Nullable ScheduledFuture<?> parkExpiry;
  // Set when the client requested an explicit graceful close (session.close) so an unexpected
  // transport drop can be distinguished from intentional teardown (#22).
  private volatile boolean explicitClose;
  // When this loop is just a forwarder for a resumed session, inbound is delegated to the resumed
  // (parked) loop instead of being dispatched here (#22).
  private volatile @Nullable SessionLoop resumeDelegate;
  private volatile @Nullable Duration heartbeatInterval;

  private final Set<JobId> ownedJobs = ConcurrentHashMap.newKeySet();

  @SuppressWarnings("unused")
  private Flow.@Nullable Subscription subscription;

  public SessionLoop(ArcpRuntime runtime, Transport transport) {
    this.runtime = runtime;
    this.transport = transport;
    this.mapper = runtime.mapper();
    this.agents = runtime.agents();
    this.resumeBuffer = new ResumeBuffer(runtime.resumeBufferCapacity());
    this.heartbeat = new HeartbeatTracker(runtime.clock());
    this.credentialBinding =
        new CredentialBinding(
            runtime.credentialProvisioner(),
            runtime.credentialRevocationStore(),
            runtime.clock(),
            this::emitJobEvent);
  }

  public String idOrPending() {
    SessionId s = sessionId;
    return s == null ? pendingId : s.value();
  }

  /** Stable key used at session insertion time; never flips even after handshake. */
  public String pendingKey() {
    return pendingId;
  }

  public void start() {
    transport.incoming().subscribe(this);
  }

  @Override
  public void onSubscribe(Flow.Subscription s) {
    this.subscription = s;
    s.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(Envelope envelope) {
    SessionLoop delegate = resumeDelegate;
    if (delegate != null) {
      // This loop only carried the resume handshake; subsequent inbound belongs to the resumed
      // session (#22).
      delegate.onNext(envelope);
      return;
    }
    heartbeat.onInbound();
    try {
      handle(envelope);
    } catch (RuntimeException e) {
      log.warn("dispatch error for {}: {}", envelope.type(), e.toString());
    }
  }

  @Override
  public void onError(Throwable throwable) {
    onTransportDropped("transport error: " + throwable.getMessage());
  }

  @Override
  public void onComplete() {
    onTransportDropped("transport closed");
  }

  private void onTransportDropped(String reason) {
    SessionLoop delegate = resumeDelegate;
    if (delegate != null) {
      // The forwarder's transport dropped; let the resumed session decide whether to park.
      delegate.onTransportDropped(reason);
      return;
    }
    // §6.3: an unexpected transport drop (not an explicit session.close) parks the session for the
    // resume window, keeping in-flight jobs alive, rather than cancelling everything (#22).
    if (!explicitClose && resumeToken != null && phase.compareAndSet(Phase.ACTIVE, Phase.PARKED)) {
      park(reason);
      return;
    }
    shutdown(reason);
  }

  private void park(String reason) {
    log.debug("session {} parked for resume: {}", sessionId, reason);
    ScheduledFuture<?> hb = heartbeatTick;
    if (hb != null) {
      hb.cancel(false);
    }
    String token = resumeToken;
    if (token == null) {
      shutdown(reason);
      return;
    }
    runtime.parkResumable(token, this);
    try {
      parkExpiry =
          runtime
              .scheduler()
              .schedule(() -> expirePark(token), runtime.resumeWindowSec(), TimeUnit.SECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      expirePark(token);
    }
  }

  private void expirePark(String token) {
    if (phase.compareAndSet(Phase.PARKED, Phase.CLOSED)) {
      runtime.removeResumable(token, this);
      teardownJobsAndSession();
    }
  }

  public void shutdown(String reason) {
    Phase prev = phase.getAndSet(Phase.CLOSED);
    if (prev == Phase.CLOSED) {
      return;
    }
    ScheduledFuture<?> hb = heartbeatTick;
    if (hb != null) {
      hb.cancel(false);
    }
    ScheduledFuture<?> pe = parkExpiry;
    if (pe != null) {
      pe.cancel(false);
    }
    if (resumeToken != null) {
      runtime.removeResumable(resumeToken, this);
    }
    teardownJobsAndSession();
  }

  /** Cancel in-flight jobs, unlink subscribers, close the transport, and deregister (#22, #101). */
  private void teardownJobsAndSession() {
    for (JobId jobId : ownedJobs) {
      JobRecord rec = runtime.job(jobId);
      if (rec == null) {
        continue;
      }
      if (!rec.status().terminal()) {
        rec.transitionTo(JobRecord.Status.CANCELLED);
        var w = rec.worker();
        if (w != null) {
          w.cancel(true);
        }
        credentialBinding.revokeAll(rec);
        cancelWatchdogs(rec);
      }
    }
    // Unlink this (now closed) session from every job's subscriber list so closed sessions are not
    // pinned in memory and never receive further fan-out (#101).
    for (JobRecord rec : runtime.jobs()) {
      rec.removeSubscribersWhere(s -> s.session() == this);
    }
    try {
      transport.close();
    } catch (RuntimeException ignored) {
      // best-effort close
    }
    runtime.removeSession(this);
  }

  private static void cancelWatchdogs(JobRecord rec) {
    ScheduledFuture<?> expiry = rec.expiryWatchdog();
    if (expiry != null) {
      expiry.cancel(false);
    }
    ScheduledFuture<?> maxRuntime = rec.maxRuntimeWatchdog();
    if (maxRuntime != null) {
      maxRuntime.cancel(false);
    }
  }

  private void handle(Envelope envelope) {
    Phase p = phase.get();
    Message m;
    try {
      m = Messages.decode(mapper, envelope);
    } catch (RuntimeException e) {
      log.warn("rejecting malformed envelope type={}: {}", envelope.type(), e.getMessage());
      // §9.5/§12: a decodable top-level envelope that fails payload validation (e.g. a non-UTC
      // expires_at) must be answered with INVALID_REQUEST rather than silently dropped (#78).
      if (p == Phase.ACTIVE) {
        sendJobErrorTopLevel(
            envelope, ErrorCode.INVALID_REQUEST, "invalid request: " + e.getMessage());
      }
      return;
    }

    if (p == Phase.AWAITING_HELLO) {
      if (m instanceof SessionHello hello) {
        doHandshake(hello);
      } else {
        log.warn("dropping pre-handshake message: {}", envelope.type());
      }
      return;
    }
    if (p == Phase.CLOSED) {
      return;
    }
    switch (m) {
      case SessionHello ignored -> log.warn("duplicate session.hello ignored");
      case SessionBye bye -> handleBye(bye);
      case SessionPing ping -> handlePing(ping);
      case SessionPong ignored -> {
        /* heartbeat already updated onNext */
      }
      case SessionAck ack -> handleAck(ack);
      case SessionListJobs listJobs -> handleListJobs(envelope, listJobs);
      case JobSubmit submit -> handleSubmit(envelope, submit);
      case JobCancel cancel -> handleCancel(envelope, cancel);
      case JobSubscribe sub -> handleSubscribe(envelope, sub);
      case JobUnsubscribe unsub -> handleUnsubscribe(unsub);
      case SessionClosed ignored -> log.warn("client-only message received: {}", m);
      case JobCancelled jc -> log.warn("client-only message received: {}", jc);
      case SessionWelcome ignored -> log.warn("client-only message received: {}", m);
      case JobAccepted ignored -> log.warn("client-only message received: {}", m);
      case JobEvent ignored -> log.warn("client-only message received: {}", m);
      case JobResult ignored -> log.warn("client-only message received: {}", m);
      case JobError ignored -> log.warn("client-only message received: {}", m);
      case JobSubscribed ignored -> log.warn("client-only message received: {}", m);
      case SessionJobs ignored -> log.warn("client-only message received: {}", m);
    }
  }

  private void doHandshake(SessionHello hello) {
    try {
      Principal pr = authenticate(hello);

      // §6.3 resume: if the hello carries a resume token for a parked session owned by the same
      // principal, reattach to it and replay missed events instead of starting fresh (#22).
      String token = hello.resumeToken();
      if (token != null) {
        SessionLoop parked = runtime.takeResumable(token);
        if (parked != null && parked.phase() == Phase.PARKED && pr.equals(parked.principal())) {
          parked.resumeOnto(this, hello.lastEventSeq());
          return;
        }
        if (parked != null) {
          // token found but not resumable by this principal: put it back for the legitimate owner.
          runtime.parkResumable(token, parked);
        }
        rejectResume();
        return;
      }

      this.principal = pr;
      this.sessionId = SessionId.generate();
      this.resumeToken = UUID.randomUUID().toString();
      this.negotiated =
          Capabilities.intersect(hello.capabilities().features(), runtime.advertised());
      // §6: only activate from AWAITING_HELLO. If a concurrent shutdown already moved us to CLOSED,
      // abort the handshake instead of resurrecting a torn-down session (#103).
      if (!this.phase.compareAndSet(Phase.AWAITING_HELLO, Phase.ACTIVE)) {
        shutdown("handshake raced shutdown");
        return;
      }

      Capabilities welcomeCaps = new Capabilities(List.of("json"), negotiated, agents.describe());
      SessionWelcome welcome =
          new SessionWelcome(
              new RuntimeInfo(runtime.runtimeName(), runtime.runtimeVersion()),
              resumeToken,
              runtime.resumeWindowSec(),
              negotiated.contains(Feature.HEARTBEAT) ? runtime.heartbeatIntervalSec() : null,
              welcomeCaps);
      send(Message.Type.SESSION_WELCOME, welcome, sessionId, null, null, null);
      log.debug("session {} accepted for {}", sessionId, pr.id());

      // §6.4: schedule heartbeat ticks if both peers negotiated heartbeat.
      if (negotiated.contains(Feature.HEARTBEAT)) {
        Duration interval = Duration.ofSeconds(runtime.heartbeatIntervalSec());
        this.heartbeatInterval = interval;
        heartbeat.onInbound();
        heartbeatTick =
            runtime
                .scheduler()
                .scheduleAtFixedRate(
                    () -> tickHeartbeat(interval),
                    interval.toMillis(),
                    interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        // If shutdown won the race after the CAS but before this assignment, cancel immediately so
        // no orphaned heartbeat task ticks forever on the shared scheduler (#103).
        if (phase.get() == Phase.CLOSED) {
          heartbeatTick.cancel(false);
        }
      }
    } catch (RuntimeException | ArcpException e) {
      log.info("handshake rejected: {}", e.getMessage());
      shutdown("auth rejected");
    }
  }

  /**
   * §6.3: reattach this parked session to {@code incoming}'s transport and replay missed events.
   */
  private void resumeOnto(SessionLoop incoming, @Nullable Long lastEventSeq) {
    ScheduledFuture<?> pe = parkExpiry;
    if (pe != null) {
      pe.cancel(false);
    }
    // Adopt the new connection's transport; route its inbound to this session; retire the
    // forwarder loop as a standalone session.
    this.transport = incoming.transport;
    incoming.resumeDelegate = this;
    runtime.removeSession(incoming);
    this.phase.set(Phase.ACTIVE);

    Capabilities welcomeCaps = new Capabilities(List.of("json"), negotiated, agents.describe());
    SessionWelcome welcome =
        new SessionWelcome(
            new RuntimeInfo(runtime.runtimeName(), runtime.runtimeVersion()),
            resumeToken,
            runtime.resumeWindowSec(),
            negotiated.contains(Feature.HEARTBEAT) ? runtime.heartbeatIntervalSec() : null,
            welcomeCaps);
    send(Message.Type.SESSION_WELCOME, welcome, sessionId, null, null, null);

    // §6.3: replay buffered envelopes the client missed (event_seq > last_event_seq).
    long from = lastEventSeq != null ? lastEventSeq : -1L;
    for (Envelope replay : resumeBuffer.since(from)) {
      try {
        transport.send(replay);
      } catch (RuntimeException e) {
        log.warn("resume replay send failed: {}", e.toString());
        break;
      }
    }

    // Reschedule heartbeat on the new connection.
    Duration interval = heartbeatInterval;
    if (negotiated.contains(Feature.HEARTBEAT) && interval != null) {
      heartbeat.onInbound();
      heartbeatTick =
          runtime
              .scheduler()
              .scheduleAtFixedRate(
                  () -> tickHeartbeat(interval),
                  interval.toMillis(),
                  interval.toMillis(),
                  TimeUnit.MILLISECONDS);
    }
    log.debug("session {} resumed", sessionId);
  }

  private void rejectResume() {
    // §6.3: unknown or expired resume token. Surface RESUME_WINDOW_EXPIRED then tear down.
    sendJobErrorTopLevel(null, ErrorCode.RESUME_WINDOW_EXPIRED, "resume token unknown or expired");
    shutdown("resume rejected");
  }

  private Principal authenticate(SessionHello hello) throws ArcpException {
    Auth auth = hello.auth();
    if (Auth.BEARER.equals(auth.scheme())) {
      String token = auth.token();
      if (token == null) {
        throw new dev.arcp.core.error.UnauthenticatedException("bearer token missing");
      }
      return runtime.verifier().verify(token);
    }
    if (Auth.ANONYMOUS.equals(auth.scheme())) {
      return new Principal("anon:" + UUID.randomUUID());
    }
    throw new dev.arcp.core.error.UnauthenticatedException(
        "unsupported auth scheme: " + auth.scheme());
  }

  private void tickHeartbeat(Duration interval) {
    if (phase.get() != Phase.ACTIVE) {
      return;
    }
    if (heartbeat.shouldClose(interval)) {
      log.info("heartbeat lost on session {}; closing", sessionId);
      shutdown("HEARTBEAT_LOST");
      return;
    }
    if (heartbeat.shouldPing(interval)) {
      SessionPing ping = new SessionPing("p_" + UUID.randomUUID(), runtime.clock().instant());
      send(Message.Type.SESSION_PING, ping, sessionId, null, null, null);
    }
  }

  private void handleBye(SessionBye bye) {
    log.debug("session {} close: {}", sessionId, bye.reason());
    // §6.7: an explicit graceful close cancels in-flight jobs (it is not a resumable drop, #22).
    explicitClose = true;
    // Acknowledge with session.closed before tearing down the transport.
    send(Message.Type.SESSION_CLOSED, new SessionClosed(bye.reason()), sessionId, null, null, null);
    shutdown("client close");
  }

  private void handlePing(SessionPing ping) {
    SessionPong pong = new SessionPong(ping.nonce(), runtime.clock().instant());
    send(Message.Type.SESSION_PONG, pong, sessionId, null, null, null);
  }

  private void handleAck(SessionAck ack) {
    lastProcessedSeq.updateAndGet(prev -> Math.max(prev, ack.lastProcessedSeq()));
  }

  private void handleListJobs(Envelope envelope, SessionListJobs req) {
    if (!negotiated.contains(Feature.LIST_JOBS)) {
      return;
    }
    JobFilter filter = req.filter() != null ? req.filter() : JobFilter.all();
    JobListing.Page page;
    try {
      page = JobListing.page(runtime.jobs(), principal, filter, req.limit(), req.cursor());
    } catch (IllegalArgumentException e) {
      // The error echoes the request envelope id so the client correlates it to this list call,
      // not an unrelated in-flight submit (#102).
      sendJobErrorTopLevel(envelope, ErrorCode.INVALID_REQUEST, "invalid list_jobs cursor");
      return;
    }
    SessionJobs response = new SessionJobs(envelope.id(), page.jobs(), page.nextCursor());
    send(Message.Type.SESSION_JOBS, response, sessionId, null, null, null);
  }

  private void handleSubmit(Envelope envelope, JobSubmit submit) {
    Principal pr = principal;
    if (pr == null) {
      return;
    }
    Instant now = runtime.clock().instant();

    // §9.5: expires_at, when present, must be in the future at submit.
    if (submit.leaseConstraints() != null) {
      Instant expires = submit.leaseConstraints().expiresAt();
      if (expires != null && !expires.isAfter(now)) {
        sendJobErrorTopLevel(
            envelope, ErrorCode.INVALID_REQUEST, "expires_at must be in the future");
        return;
      }
    }

    // §7.2: idempotency. Identical (principal, key, full-submit fingerprint) returns prior job_id.
    String idempotencyKey = submit.idempotencyKey();
    if (idempotencyKey != null) {
      String fingerprint = IdempotencyFingerprint.of(mapper, submit);
      JobId fresh = JobId.generate();
      var conflict = runtime.idempotency().claim(pr, idempotencyKey, fingerprint, fresh);
      if (conflict != null) {
        if (runtime.idempotency().matchesPayload(pr, idempotencyKey, fingerprint)) {
          JobRecord prior = runtime.job(conflict.existing());
          if (prior != null) {
            emitReplayAccepted(prior, envelope.traceId());
            return;
          }
          // Same key + identical params, but the prior job was never registered or was removed (a
          // failed earlier accept). Release the stale claim and re-claim so this identical retry is
          // accepted rather than poisoned with DUPLICATE_KEY for the whole TTL (#90).
          runtime.idempotency().release(pr, idempotencyKey, conflict.existing());
          if (runtime.idempotency().claim(pr, idempotencyKey, fingerprint, fresh) != null) {
            sendJobErrorTopLevel(
                envelope,
                ErrorCode.DUPLICATE_KEY,
                "idempotency_key reuse with conflicting parameters: " + idempotencyKey);
            return;
          }
          acceptJob(envelope, submit, pr, now, fresh, idempotencyKey);
          return;
        }
        sendJobErrorTopLevel(
            envelope,
            ErrorCode.DUPLICATE_KEY,
            "idempotency_key reuse with conflicting parameters: " + idempotencyKey);
        return;
      }
      acceptJob(envelope, submit, pr, now, fresh, idempotencyKey);
      return;
    }
    acceptJob(envelope, submit, pr, now, JobId.generate(), null);
  }

  private void releaseIdempotency(Principal pr, @Nullable String idempotencyKey, JobId jobId) {
    if (idempotencyKey != null) {
      runtime.idempotency().release(pr, idempotencyKey, jobId);
    }
  }

  private void emitReplayAccepted(JobRecord prior, @Nullable TraceId traceId) {
    // §7.2: replay the budget captured at the original acceptance, not the live (decremented)
    // counters, so an identical retry returns the same job.accepted payload (#79).
    Map<String, BigDecimal> budgetSnapshot =
        prior.acceptedBudget() != null ? prior.acceptedBudget() : prior.budget().snapshot();
    JobAccepted accepted =
        new JobAccepted(
            prior.jobId(),
            prior.resolvedAgent(),
            prior.lease(),
            prior.constraints().expiresAt() != null ? prior.constraints() : null,
            budgetSnapshot.isEmpty() ? null : budgetSnapshot,
            nullableWireCredentials(prior),
            prior.createdAt(),
            traceId);
    send(Message.Type.JOB_ACCEPTED, accepted, sessionId, traceId, prior.jobId(), null);
  }

  private void acceptJob(
      Envelope envelope,
      JobSubmit submit,
      Principal pr,
      Instant now,
      JobId jobId,
      @Nullable String idempotencyKey) {
    AgentRegistry.Resolved resolved;
    try {
      resolved = agents.resolve(submit.agent());
    } catch (dev.arcp.core.error.AgentVersionNotAvailableException e) {
      releaseIdempotency(pr, idempotencyKey, jobId);
      sendJobErrorTopLevel(envelope, ErrorCode.AGENT_VERSION_NOT_AVAILABLE, e.getMessage());
      return;
    } catch (dev.arcp.core.error.AgentNotAvailableException e) {
      releaseIdempotency(pr, idempotencyKey, jobId);
      sendJobErrorTopLevel(envelope, ErrorCode.AGENT_NOT_AVAILABLE, e.getMessage());
      return;
    }

    Lease lease = submit.leaseRequest() != null ? submit.leaseRequest() : Lease.empty();
    LeaseConstraints constraints =
        submit.leaseConstraints() != null ? submit.leaseConstraints() : LeaseConstraints.none();
    BudgetCounters budget = new BudgetCounters(lease.budget());
    TraceId traceId = envelope.traceId();
    JobRecord record =
        new JobRecord(
            jobId,
            resolved.wire(),
            pr,
            lease,
            constraints,
            budget,
            now,
            traceId,
            runtime.resumeBufferCapacity());
    runtime.registerJob(record);
    ownedJobs.add(jobId);

    List<Credential> credentials = List.of();
    if (negotiated.contains(Feature.PROVISIONED_CREDENTIALS)) {
      try {
        List<IssuedCredential> issued =
            runtime.credentialProvisioner().issue(lease, constraints, issueContext(record)).join();
        credentials = credentialBinding.attach(record, issued);
      } catch (RuntimeException e) {
        ownedJobs.remove(jobId);
        runtime.removeJob(jobId);
        releaseIdempotency(pr, idempotencyKey, jobId);
        Throwable root = rootCause(e);
        if (root instanceof UpstreamBudgetExhaustedException budgetError) {
          sendJobErrorTopLevel(envelope, ErrorCode.BUDGET_EXHAUSTED, budgetError.getMessage());
        } else {
          sendJobErrorTopLevel(
              envelope,
              ErrorCode.INTERNAL_ERROR,
              root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName());
        }
        return;
      }
    }

    Map<String, BigDecimal> budgetSnapshot = budget.snapshot();
    // §7.2: capture the budget returned at acceptance so an idempotent replay returns the same
    // payload regardless of intervening spend (#79).
    record.setAcceptedBudget(budgetSnapshot);
    JobAccepted accepted =
        new JobAccepted(
            jobId,
            resolved.wire(),
            lease,
            constraints.expiresAt() != null ? constraints : null,
            budgetSnapshot.isEmpty() ? null : budgetSnapshot,
            credentials.isEmpty() ? null : credentials,
            now,
            traceId);
    send(Message.Type.JOB_ACCEPTED, accepted, sessionId, traceId, jobId, null);

    // Start the worker before scheduling watchdogs so a short-fused watchdog cannot fire in the gap
    // before setWorker and lose the interrupt (#104).
    record.setWorker(runtime.workerPool().submit(() -> runJob(record, resolved.agent(), submit)));

    // §9.5 watchdog: terminate the job if the lease expires.
    if (constraints.expiresAt() != null) {
      long delayMillis = Duration.between(now, constraints.expiresAt()).toMillis();
      ScheduledFuture<?> watchdog =
          runtime
              .scheduler()
              .schedule(
                  () -> terminateExpiredJob(record),
                  Math.max(0, delayMillis),
                  TimeUnit.MILLISECONDS);
      record.setExpiryWatchdog(watchdog);
    }

    // §7.1/§12 watchdog: enforce max_runtime_sec by emitting TIMEOUT if exceeded (#89).
    if (submit.maxRuntimeSec() != null && submit.maxRuntimeSec() > 0) {
      ScheduledFuture<?> timeout =
          runtime
              .scheduler()
              .schedule(
                  () -> terminateTimedOutJob(record),
                  submit.maxRuntimeSec().longValue(),
                  TimeUnit.SECONDS);
      record.setMaxRuntimeWatchdog(timeout);
    }
  }

  private void terminateExpiredJob(JobRecord record) {
    // §9.5/§7.3: lease expiry is a LEASE_EXPIRED error with final_status "error", not a timeout
    // (#74). The terminal record status is ERROR.
    if (record.transitionTo(JobRecord.Status.ERROR)) {
      var w = record.worker();
      if (w != null) {
        w.cancel(true);
      }
      emitJobError(
          record,
          JobError.ERROR,
          ErrorCode.LEASE_EXPIRED,
          "lease expired at " + record.constraints().expiresAt());
      credentialBinding.revokeAll(record);
    }
  }

  private void terminateTimedOutJob(JobRecord record) {
    // §12 TIMEOUT: the job exceeded max_runtime_sec → final_status "timed_out" (#89).
    if (record.transitionTo(JobRecord.Status.TIMED_OUT)) {
      var w = record.worker();
      if (w != null) {
        w.cancel(true);
      }
      emitJobError(record, JobError.TIMED_OUT, ErrorCode.TIMEOUT, "job exceeded max_runtime_sec");
      credentialBinding.revokeAll(record);
    }
  }

  private void runJob(JobRecord record, Agent agent, JobSubmit submit) {
    // If a watchdog/cancel already moved the record to a terminal state in the gap before this
    // worker started, do not start the agent (#104).
    if (!record.transitionTo(JobRecord.Status.RUNNING)) {
      return;
    }
    JobInput input =
        new JobInput(
            submit.input(),
            record.jobId(),
            sessionId,
            record.traceId(),
            record.lease(),
            wireCredentials(record));
    LeaseGuard guard = new LeaseGuard(record.lease(), record.constraints(), runtime.clock());

    JobContext ctx =
        new JobContext() {
          @Override
          public void emit(EventBody body) {
            if (record.status().terminal()) {
              return;
            }
            if (body instanceof MetricEvent metric
                && metric.unit() != null
                && metric.name() != null
                && metric.value() != null
                && metric.name().startsWith("cost.")
                // §9.6: cost.budget.* are telemetry gauges (e.g. cost.budget.remaining carries the
                // remaining balance), not spend reports — never decrement on them (#108).
                && !metric.name().startsWith("cost.budget.")
                && record.budget().tracks(metric.unit())) {
              record.budget().decrement(metric.unit(), metric.value());
            }
            emitJobEvent(record, body);
          }

          @Override
          public boolean cancelled() {
            // §9.5: any terminal status (CANCELLED, TIMED_OUT, ERROR) means a polling agent should
            // stop, not just CANCELLED (#104).
            return Thread.currentThread().isInterrupted()
                || record.status().terminal()
                || phase.get() == Phase.CLOSED;
          }

          @Override
          public void authorize(String namespace, String pattern)
              throws dev.arcp.core.error.PermissionDeniedException,
                  dev.arcp.core.error.LeaseExpiredException,
                  dev.arcp.core.error.BudgetExhaustedException {
            guard.authorize(namespace, pattern);
            record.budget().ensureAllPositive();
          }

          @Override
          public List<Credential> credentials() {
            return wireCredentials(record);
          }

          @Override
          public void rotateCredential(CredentialId id, String newValue) {
            record.credentials().stream()
                .filter(issued -> issued.wire().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown credential id: " + id));
            List<IssuedCredential> reissued =
                runtime
                    .credentialProvisioner()
                    .issue(record.lease(), record.constraints(), this)
                    .join();
            if (reissued.isEmpty()) {
              // §9.8.2/§14: without a freshly minted credential there is nothing to rotate to; fail
              // loudly rather than aliasing the current handle (which rotate() would then revoke,
              // killing the live credential) (#98).
              throw new IllegalStateException(
                  "credential rotation produced no credential for " + id);
            }
            // Track and revoke every minted credential except the one we keep, so no minted
            // credential is left with untracked, unrevoked spend authority upstream (#98).
            IssuedCredential selected = reissued.get(0);
            for (int i = 1; i < reissued.size(); i++) {
              credentialBinding.revokeMinted(reissued.get(i));
            }
            Credential wire = selected.wire();
            IssuedCredential next =
                new IssuedCredential(
                    new Credential(
                        wire.id(),
                        wire.scheme(),
                        newValue,
                        wire.endpoint(),
                        wire.profile(),
                        wire.constraints()),
                    selected.providerHandle());
            credentialBinding.rotate(record, id, next);
          }
        };

    try {
      JobOutcome outcome = agent.run(input, ctx);
      // If a cancel/watchdog already terminated this job (even if the agent swallowed the
      // interrupt and returned normally), the terminal message was already emitted — do not emit a
      // second, possibly contradictory one (#92).
      if (record.status().terminal()) {
        return;
      }
      switch (outcome) {
        case JobOutcome.Success s -> {
          if (record.transitionTo(JobRecord.Status.SUCCESS)) {
            JobResult result =
                new JobResult(
                    JobResult.SUCCESS, s.resultId(), s.resultSize(), s.inline(), s.summary());
            long seq = nextSeq();
            sendJobMessage(record, Message.Type.JOB_RESULT, result, seq);
            // §7.6/§13.3: subscribers must also observe termination (#93).
            fanOutTerminal(record, Message.Type.JOB_RESULT, result);
            credentialBinding.revokeAll(record);
          }
        }
        case JobOutcome.Failure f -> {
          if (record.transitionTo(JobRecord.Status.ERROR)) {
            emitJobError(record, JobError.ERROR, f.code(), f.message());
            credentialBinding.revokeAll(record);
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // If the watchdog or an external cancel already transitioned this
      // record to a terminal state, do not emit a competing error.
      if (record.transitionTo(JobRecord.Status.CANCELLED)) {
        emitJobError(record, JobError.CANCELLED, ErrorCode.CANCELLED, "interrupted");
        credentialBinding.revokeAll(record);
      }
    } catch (dev.arcp.core.error.LeaseExpiredException e) {
      // §7.3: only emit a terminal error if this thread wins the transition; otherwise a watchdog
      // or
      // cancel already produced the single terminal message (#92).
      if (record.transitionTo(JobRecord.Status.ERROR)) {
        emitJobError(record, JobError.ERROR, ErrorCode.LEASE_EXPIRED, e.getMessage());
        credentialBinding.revokeAll(record);
      }
    } catch (dev.arcp.core.error.PermissionDeniedException e) {
      if (record.transitionTo(JobRecord.Status.ERROR)) {
        emitJobError(record, JobError.ERROR, ErrorCode.PERMISSION_DENIED, e.getMessage());
        credentialBinding.revokeAll(record);
      }
    } catch (dev.arcp.core.error.BudgetExhaustedException e) {
      if (record.transitionTo(JobRecord.Status.ERROR)) {
        emitJobError(record, JobError.ERROR, ErrorCode.BUDGET_EXHAUSTED, e.getMessage());
        credentialBinding.revokeAll(record);
      }
    } catch (Exception e) {
      if (record.transitionTo(JobRecord.Status.ERROR)) {
        emitJobError(
            record,
            JobError.ERROR,
            ErrorCode.INTERNAL_ERROR,
            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        credentialBinding.revokeAll(record);
      }
    } finally {
      // Cancel both watchdogs once the worker finishes so no stray scheduler task remains (#89).
      cancelWatchdogs(record);
      scheduleEviction(record);
    }
  }

  private void handleCancel(Envelope envelope, JobCancel cancel) {
    JobId jobId = envelope.jobId();
    if (jobId == null) {
      // §12: a cancel without a job_id is malformed, not silently dropped (#95).
      sendJobErrorTopLevel(envelope, ErrorCode.INVALID_REQUEST, "job.cancel missing job_id");
      return;
    }
    JobRecord rec = runtime.job(jobId);
    // §7.4/§7.6: unknown, evicted, or not-visible (non-submitter) jobs all answer JOB_NOT_FOUND so
    // a cancel is never silently ignored and job existence is not leaked to non-submitters (#95).
    if (rec == null || !rec.principal().equals(principal)) {
      sendJobErrorTopLevel(envelope, ErrorCode.JOB_NOT_FOUND, "job not found: " + jobId);
      return;
    }
    String reason = cancel.reason() != null ? cancel.reason() : "cancelled";
    if (rec.transitionTo(JobRecord.Status.CANCELLED)) {
      var w = rec.worker();
      if (w != null) {
        w.cancel(true);
      }
      // §7.4: acknowledge with job.cancelled, then emit the terminal job.error CANCELLED.
      sendJobMessage(rec, Message.Type.JOB_CANCELLED, new JobCancelled(reason), nextSeq());
      emitJobError(rec, JobError.CANCELLED, ErrorCode.CANCELLED, reason);
      credentialBinding.revokeAll(rec);
    } else {
      // Already terminal: acknowledge idempotently rather than going silent.
      sendJobMessage(rec, Message.Type.JOB_CANCELLED, new JobCancelled(reason), nextSeq());
    }
  }

  private JobContext issueContext(JobRecord record) {
    LeaseGuard guard = new LeaseGuard(record.lease(), record.constraints(), runtime.clock());
    return new JobContext() {
      @Override
      public void emit(EventBody body) {
        emitJobEvent(record, body);
      }

      @Override
      public boolean cancelled() {
        return record.status().terminal() || phase.get() == Phase.CLOSED;
      }

      @Override
      public void authorize(String namespace, String pattern)
          throws dev.arcp.core.error.PermissionDeniedException,
              dev.arcp.core.error.LeaseExpiredException,
              dev.arcp.core.error.BudgetExhaustedException {
        guard.authorize(namespace, pattern);
        record.budget().ensureAllPositive();
      }

      @Override
      public List<Credential> credentials() {
        return wireCredentials(record);
      }
    };
  }

  private static List<Credential> wireCredentials(JobRecord record) {
    return record.credentials().stream().map(IssuedCredential::wire).toList();
  }

  private static @Nullable List<Credential> nullableWireCredentials(JobRecord record) {
    List<Credential> credentials = wireCredentials(record);
    return credentials.isEmpty() ? null : credentials;
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable root = throwable;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root;
  }

  private void handleSubscribe(Envelope envelope, JobSubscribe sub) {
    if (!negotiated.contains(Feature.SUBSCRIBE)) {
      return;
    }
    JobRecord rec = runtime.job(sub.jobId());
    if (rec == null || !rec.principal().equals(principal)) {
      // Echo the request envelope id so the client correlates this error to the subscribe call, not
      // an unrelated in-flight submit (#102).
      sendJobErrorTopLevel(
          envelope, ErrorCode.JOB_NOT_FOUND, "job not found or not visible: " + sub.jobId());
      return;
    }
    boolean alreadySubscribed =
        rec.subscribers().stream()
            .anyMatch(s -> s.session() == this && s.jobId().equals(rec.jobId()));
    if (!alreadySubscribed) {
      rec.addSubscriber(new JobRecord.Subscriber(this, rec.jobId()));
    }
    boolean wantHistory = Boolean.TRUE.equals(sub.history());
    long subscribedFrom = sub.fromEventSeq() != null ? sub.fromEventSeq() : 0;
    long now = eventSeq.get();
    JobSubscribed response =
        new JobSubscribed(
            rec.jobId(),
            rec.status().wire(),
            rec.resolvedAgent(),
            rec.lease(),
            null,
            rec.traceId(),
            now,
            wantHistory);
    send(Message.Type.JOB_SUBSCRIBED, response, sessionId, rec.traceId(), rec.jobId(), null);

    if (wantHistory) {
      // §8.1/§8.3/§14: re-envelope each replayed event with this (subscriber) session's session_id
      // and a freshly allocated event_seq, and never echo credential material to a subscriber
      // (#94).
      boolean isOwner = ownedJobs.contains(rec.jobId());
      for (JobRecord.RecordedEvent replay : rec.eventsSince(subscribedFrom)) {
        JobEvent event = replay.event();
        if (!isOwner && isCredentialRotated(event)) {
          continue; // never replay credential_rotated (with its value) to a non-owning subscriber
        }
        sendJobMessage(rec, Message.Type.JOB_EVENT, event, nextSeq());
      }
    }
  }

  private static boolean isCredentialRotated(JobEvent event) {
    return "status".equals(event.eventKind())
        && event.body() != null
        && "credential_rotated".equals(event.body().path("phase").asText(""));
  }

  private void handleUnsubscribe(JobUnsubscribe unsub) {
    JobRecord rec = runtime.job(unsub.jobId());
    if (rec != null) {
      rec.removeSubscribersWhere(s -> s.session() == this);
    }
  }

  private void emitJobEvent(JobRecord record, EventBody body) {
    long seq = nextSeq();
    record.setLastEventSeq(seq);
    JobEvent event =
        new JobEvent(body.kind().wire(), runtime.clock().instant(), mapper.valueToTree(body));
    send(Message.Type.JOB_EVENT, event, sessionId, record.traceId(), record.jobId(), seq);
    // Record the decoded event (not the wire envelope) so history replay can re-envelope it per
    // subscriber with that subscriber's own session_id and event_seq (#94).
    record.recordEvent(seq, event);

    // §14: credential_rotated carries the new credential value, which only the submitting session
    // may receive — never fan it out to subscribers (#75).
    boolean redactFromSubscribers =
        body instanceof StatusEvent se && "credential_rotated".equals(se.phase());
    if (redactFromSubscribers) {
      return;
    }
    for (JobRecord.Subscriber sub : record.subscribers()) {
      if (sub.session() != this) {
        // §7.6/§8.3: allocate the event_seq from the subscriber session's own counter (#76).
        sub.session().forwardJobMessage(record, Message.Type.JOB_EVENT, event);
      }
    }
  }

  /** Forward a job message to a subscriber session using this (subscriber) session's seq space. */
  void forwardJobMessage(JobRecord rec, Message.Type type, Message msg) {
    sendJobMessage(rec, type, msg, nextSeq());
  }

  /** Deliver a terminal message (job.result/job.error) to every subscriber session (§7.6, #93). */
  private void fanOutTerminal(JobRecord record, Message.Type type, Message msg) {
    for (JobRecord.Subscriber sub : record.subscribers()) {
      if (sub.session() != this) {
        sub.session().forwardJobMessage(record, type, msg);
      }
    }
  }

  private void emitJobError(JobRecord record, String finalStatus, ErrorCode code, String message) {
    JobError err = JobError.fromJson(finalStatus, code, message, null, null);
    sendJobMessage(record, Message.Type.JOB_ERROR, err, nextSeq());
    // §7.6/§13.3: subscribers must also observe terminal errors (#93).
    fanOutTerminal(record, Message.Type.JOB_ERROR, err);
  }

  private void scheduleEviction(JobRecord record) {
    // §7.6: keep terminal jobs visible to list_jobs / late subscribe for the resume window, then
    // evict so the runtime's job map and event history do not grow without bound (#101).
    try {
      runtime
          .scheduler()
          .schedule(
              () -> {
                ownedJobs.remove(record.jobId());
                runtime.removeJob(record.jobId());
              },
              Math.max(0, runtime.resumeWindowSec()),
              TimeUnit.SECONDS);
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
      // scheduler already shut down (runtime closing); nothing to evict
    }
  }

  private void sendJobErrorTopLevel(@Nullable Envelope origin, ErrorCode code, String message) {
    JobError err = JobError.fromJson(JobError.ERROR, code, message, null, null);
    // Echo the originating request's envelope id so the client can correlate a top-level error to
    // the exact request that caused it instead of failing an unrelated pending submit (#102).
    MessageId responseId = origin != null ? origin.id() : MessageId.generate();
    sendWithId(
        responseId,
        Message.Type.JOB_ERROR,
        err,
        sessionId,
        origin != null ? origin.traceId() : null,
        origin != null ? origin.jobId() : null,
        null);
  }

  private long nextSeq() {
    return eventSeq.incrementAndGet();
  }

  private void sendJobMessage(JobRecord rec, Message.Type type, Message msg, long seq) {
    send(type, msg, sessionId, rec.traceId(), rec.jobId(), seq);
  }

  private @Nullable Envelope send(
      Message.Type type,
      Message payload,
      @Nullable SessionId sid,
      @Nullable TraceId tid,
      @Nullable JobId jid,
      @Nullable Long seq) {
    return sendWithId(MessageId.generate(), type, payload, sid, tid, jid, seq);
  }

  private @Nullable Envelope sendWithId(
      MessageId id,
      Message.Type type,
      Message payload,
      @Nullable SessionId sid,
      @Nullable TraceId tid,
      @Nullable JobId jid,
      @Nullable Long seq) {
    if (phase.get() == Phase.CLOSED) {
      return null;
    }
    ObjectNode payloadJson = Messages.encodePayload(mapper, payload);
    Envelope env = new Envelope(Envelope.VERSION, id, type.wire(), sid, tid, jid, seq, payloadJson);
    if (seq != null) {
      resumeBuffer.record(env);
    }
    // §6.3: while parked the transport is gone; buffer sequenced messages for replay on resume and
    // do not tear the session down (#22).
    if (phase.get() == Phase.PARKED) {
      return env;
    }
    try {
      transport.send(env);
    } catch (RuntimeException e) {
      log.warn("send failed: {}", e.toString());
      if (phase.get() == Phase.ACTIVE) {
        shutdown("send failure");
      }
    }
    return env;
  }

  public Set<Feature> negotiated() {
    return java.util.Collections.unmodifiableSet(negotiated);
  }

  public @Nullable SessionId sessionId() {
    return sessionId;
  }

  public @Nullable Principal principal() {
    return principal;
  }

  public Phase phase() {
    return phase.get();
  }
}
