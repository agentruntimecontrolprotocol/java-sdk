package dev.arcp.runtime.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobCancel;
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
import dev.arcp.core.messages.RuntimeInfo;
import dev.arcp.core.messages.SessionAck;
import dev.arcp.core.messages.SessionBye;
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
import dev.arcp.runtime.heartbeat.HeartbeatTracker;
import dev.arcp.runtime.lease.BudgetCounters;
import dev.arcp.runtime.lease.LeaseGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Per-transport runtime session: handshake, message dispatch, job lifecycle,
 * heartbeats, lease enforcement, idempotency, and subscribe fan-out.
 */
public final class SessionLoop implements Flow.Subscriber<Envelope> {

    private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

    public enum Phase {
        AWAITING_HELLO,
        ACTIVE,
        CLOSED
    }

    private final ArcpRuntime runtime;
    private final Transport transport;
    private final ObjectMapper mapper;
    private final AgentRegistry agents;
    private final String pendingId = "pending:" + UUID.randomUUID();

    private volatile Phase phase = Phase.AWAITING_HELLO;
    private volatile @Nullable SessionId sessionId;
    private volatile @Nullable Principal principal;
    private volatile Set<Feature> negotiated = EnumSet.noneOf(Feature.class);
    private volatile @Nullable String resumeToken;

    private final AtomicLong eventSeq = new AtomicLong(0);
    private final AtomicLong lastProcessedSeq = new AtomicLong(-1);
    private final ResumeBuffer resumeBuffer;
    private final HeartbeatTracker heartbeat;
    private @Nullable ScheduledFuture<?> heartbeatTick;

    private final ConcurrentHashMap<JobId, JobRecord> jobs = new ConcurrentHashMap<>();

    @SuppressWarnings("unused")
    private Flow.@Nullable Subscription subscription;

    public SessionLoop(ArcpRuntime runtime, Transport transport) {
        this.runtime = runtime;
        this.transport = transport;
        this.mapper = runtime.mapper();
        this.agents = runtime.agents();
        this.resumeBuffer = new ResumeBuffer(runtime.resumeBufferCapacity());
        this.heartbeat = new HeartbeatTracker(runtime.clock());
    }

    public String idOrPending() {
        SessionId s = sessionId;
        return s == null ? pendingId : s.value();
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
        heartbeat.onInbound();
        try {
            handle(envelope);
        } catch (Exception e) {
            log.warn("dispatch error for {}: {}", envelope.type(), e.toString());
        }
    }

    @Override
    public void onError(Throwable throwable) {
        shutdown("transport error: " + throwable.getMessage());
    }

    @Override
    public void onComplete() {
        shutdown("transport closed");
    }

    public void shutdown(String reason) {
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSED;
        ScheduledFuture<?> hb = heartbeatTick;
        if (hb != null) {
            hb.cancel(false);
        }
        for (JobRecord rec : jobs.values()) {
            if (!rec.status().terminal()) {
                rec.transitionTo(JobRecord.Status.CANCELLED);
                var w = rec.worker();
                if (w != null) {
                    w.cancel(true);
                }
                ScheduledFuture<?> watchdog = rec.expiryWatchdog();
                if (watchdog != null) {
                    watchdog.cancel(false);
                }
            }
        }
        try {
            transport.close();
        } catch (RuntimeException ignored) {
            // best-effort close
        }
        runtime.removeSession(this);
    }

    // ---------------------------------------------------------- dispatch

    private void handle(Envelope envelope) {
        Phase p = phase;
        Message m;
        try {
            m = Messages.decode(mapper, envelope);
        } catch (RuntimeException e) {
            log.warn("rejecting malformed envelope type={}: {}", envelope.type(), e.getMessage());
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
            case SessionPong ignored -> { /* heartbeat already updated onNext */ }
            case SessionAck ack -> handleAck(ack);
            case SessionListJobs listJobs -> handleListJobs(envelope.id(), listJobs);
            case JobSubmit submit -> handleSubmit(envelope, submit);
            case JobCancel cancel -> handleCancel(envelope, cancel);
            case JobSubscribe sub -> handleSubscribe(sub);
            case JobUnsubscribe unsub -> handleUnsubscribe(unsub);
            case SessionWelcome ignored -> log.warn("client-only message received: {}", m);
            case JobAccepted ignored -> log.warn("client-only message received: {}", m);
            case JobEvent ignored -> log.warn("client-only message received: {}", m);
            case JobResult ignored -> log.warn("client-only message received: {}", m);
            case JobError ignored -> log.warn("client-only message received: {}", m);
            case JobSubscribed ignored -> log.warn("client-only message received: {}", m);
            case SessionJobs ignored -> log.warn("client-only message received: {}", m);
        }
    }

    // ---------------------------------------------------------- handshake

    private void doHandshake(SessionHello hello) {
        try {
            Principal pr = authenticate(hello);
            this.principal = pr;
            this.sessionId = SessionId.generate();
            this.resumeToken = UUID.randomUUID().toString();
            this.negotiated = Capabilities.intersect(
                    hello.capabilities().features(), runtime.advertised());
            this.phase = Phase.ACTIVE;

            Capabilities welcomeCaps = new Capabilities(
                    List.of("json"), negotiated, agents.describe());
            SessionWelcome welcome = new SessionWelcome(
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
                heartbeat.onInbound();
                heartbeatTick = runtime.scheduler().scheduleAtFixedRate(
                        () -> tickHeartbeat(interval),
                        interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException | ArcpException e) {
            log.info("handshake rejected: {}", e.getMessage());
            shutdown("auth rejected");
        }
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

    // ---------------------------------------------------------- heartbeat

    private void tickHeartbeat(Duration interval) {
        if (phase != Phase.ACTIVE) {
            return;
        }
        if (heartbeat.shouldClose(interval)) {
            log.info("heartbeat lost on session {}; closing", sessionId);
            shutdown("HEARTBEAT_LOST");
            return;
        }
        if (heartbeat.shouldPing(interval)) {
            SessionPing ping = new SessionPing(
                    "p_" + UUID.randomUUID(), runtime.clock().instant());
            send(Message.Type.SESSION_PING, ping, sessionId, null, null, null);
        }
    }

    // ---------------------------------------------------------- control

    private void handleBye(SessionBye bye) {
        log.debug("session {} bye: {}", sessionId, bye.reason());
        shutdown("client bye");
    }

    private void handlePing(SessionPing ping) {
        SessionPong pong = new SessionPong(ping.nonce(), runtime.clock().instant());
        send(Message.Type.SESSION_PONG, pong, sessionId, null, null, null);
    }

    private void handleAck(SessionAck ack) {
        lastProcessedSeq.updateAndGet(prev -> Math.max(prev, ack.lastProcessedSeq()));
    }

    private void handleListJobs(MessageId requestId, SessionListJobs req) {
        if (!negotiated.contains(Feature.LIST_JOBS)) {
            return;
        }
        JobFilter filter = req.filter() != null ? req.filter() : JobFilter.all();
        List<JobSummary> matching = new ArrayList<>();
        for (JobRecord rec : jobs.values()) {
            if (!rec.principal().equals(principal)) {
                continue;
            }
            if (filter.status() != null && !filter.status().contains(rec.status().wire())) {
                continue;
            }
            if (filter.agent() != null && !filter.agent().equals(rec.resolvedAgent())
                    && !filter.agent().equals(rec.resolvedAgent().split("@", 2)[0])) {
                continue;
            }
            if (filter.createdAfter() != null && !rec.createdAt().isAfter(filter.createdAfter())) {
                continue;
            }
            matching.add(new JobSummary(
                    rec.jobId(),
                    rec.resolvedAgent(),
                    rec.status().wire(),
                    rec.lease(),
                    null,
                    rec.createdAt(),
                    rec.traceId(),
                    rec.lastEventSeq()));
        }
        SessionJobs response = new SessionJobs(requestId, matching, null);
        send(Message.Type.SESSION_JOBS, response, sessionId, null, null, null);
    }

    // ---------------------------------------------------------- jobs

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
                sendJobErrorTopLevel(envelope, ErrorCode.INVALID_REQUEST,
                        "expires_at must be in the future");
                return;
            }
        }

        // §7.2: idempotency. Identical (principal, key, payload) returns the prior job_id.
        String idempotencyKey = submit.idempotencyKey();
        if (idempotencyKey != null) {
            int payloadHash = submit.input().hashCode() ^ submit.agent().wire().hashCode();
            JobId fresh = JobId.generate();
            var conflict = runtime.idempotency().claim(pr, idempotencyKey, payloadHash, fresh);
            if (conflict != null) {
                if (runtime.idempotency().matchesPayload(pr, idempotencyKey, payloadHash)) {
                    JobRecord prior = jobs.get(conflict.existing());
                    if (prior != null) {
                        emitReplayAccepted(prior, envelope.traceId());
                        return;
                    }
                }
                sendJobErrorTopLevel(envelope, ErrorCode.DUPLICATE_KEY,
                        "idempotency_key reuse with conflicting parameters: " + idempotencyKey);
                return;
            }
            acceptJob(envelope, submit, pr, now, fresh);
            return;
        }
        acceptJob(envelope, submit, pr, now, JobId.generate());
    }

    private void emitReplayAccepted(JobRecord prior, @Nullable TraceId traceId) {
        Map<String, BigDecimal> budgetSnapshot = prior.budget().snapshot();
        JobAccepted accepted = new JobAccepted(
                prior.jobId(),
                prior.resolvedAgent(),
                prior.lease(),
                prior.constraints().expiresAt() != null ? prior.constraints() : null,
                budgetSnapshot.isEmpty() ? null : budgetSnapshot,
                prior.createdAt(),
                traceId);
        send(Message.Type.JOB_ACCEPTED, accepted, sessionId, traceId, prior.jobId(), null);
    }

    private void acceptJob(
            Envelope envelope, JobSubmit submit, Principal pr, Instant now, JobId jobId) {
        AgentRegistry.Resolved resolved;
        try {
            resolved = agents.resolve(submit.agent());
        } catch (dev.arcp.core.error.AgentVersionNotAvailableException e) {
            sendJobErrorTopLevel(envelope, ErrorCode.AGENT_VERSION_NOT_AVAILABLE, e.getMessage());
            return;
        } catch (dev.arcp.core.error.AgentNotAvailableException e) {
            sendJobErrorTopLevel(envelope, ErrorCode.AGENT_NOT_AVAILABLE, e.getMessage());
            return;
        }

        Lease lease = submit.leaseRequest() != null ? submit.leaseRequest() : Lease.empty();
        LeaseConstraints constraints = submit.leaseConstraints() != null
                ? submit.leaseConstraints() : LeaseConstraints.none();
        BudgetCounters budget = new BudgetCounters(lease.budget());
        TraceId traceId = envelope.traceId();
        JobRecord record = new JobRecord(
                jobId, resolved.wire(), pr, lease, constraints, budget, now, traceId);
        jobs.put(jobId, record);

        Map<String, BigDecimal> budgetSnapshot = budget.snapshot();
        JobAccepted accepted = new JobAccepted(
                jobId,
                resolved.wire(),
                lease,
                constraints.expiresAt() != null ? constraints : null,
                budgetSnapshot.isEmpty() ? null : budgetSnapshot,
                now,
                traceId);
        send(Message.Type.JOB_ACCEPTED, accepted, sessionId, traceId, jobId, null);

        // §9.5 watchdog: schedule a terminator if the lease has an expiry.
        if (constraints.expiresAt() != null) {
            long delayMillis = Duration.between(now, constraints.expiresAt()).toMillis();
            if (delayMillis > 0) {
                ScheduledFuture<?> watchdog = runtime.scheduler().schedule(
                        () -> terminateExpiredJob(record),
                        delayMillis, TimeUnit.MILLISECONDS);
                record.setExpiryWatchdog(watchdog);
            }
        }

        record.setWorker(runtime.workerPool().submit(
                () -> runJob(record, resolved.agent(), submit)));
    }

    private void terminateExpiredJob(JobRecord record) {
        if (record.transitionTo(JobRecord.Status.ERROR)) {
            var w = record.worker();
            if (w != null) {
                w.cancel(true);
            }
            emitJobError(record, JobError.ERROR, ErrorCode.LEASE_EXPIRED,
                    "lease expired at " + record.constraints().expiresAt());
        }
    }

    private void runJob(JobRecord record, Agent agent, JobSubmit submit) {
        record.transitionTo(JobRecord.Status.RUNNING);
        JobInput input = new JobInput(
                submit.input(), record.jobId(), sessionId, record.traceId(), record.lease());
        LeaseGuard guard = new LeaseGuard(record.lease(), record.constraints(), runtime.clock());

        JobContext ctx = new JobContext() {
            @Override
            public void emit(EventBody body) {
                if (record.status().terminal()) {
                    return;
                }
                if (body instanceof MetricEvent metric
                        && metric.unit() != null
                        && metric.name() != null
                        && metric.name().startsWith("cost.")
                        && record.budget().tracks(metric.unit())) {
                    record.budget().decrement(metric.unit(), metric.value());
                }
                emitJobEvent(record, body);
            }

            @Override
            public boolean cancelled() {
                return Thread.currentThread().isInterrupted()
                        || record.status() == JobRecord.Status.CANCELLED
                        || phase == Phase.CLOSED;
            }

            @Override
            public void authorize(String namespace, String pattern)
                    throws dev.arcp.core.error.PermissionDeniedException,
                            dev.arcp.core.error.LeaseExpiredException,
                            dev.arcp.core.error.BudgetExhaustedException {
                guard.authorize(namespace, pattern);
                record.budget().ensureAllPositive();
            }
        };

        try {
            JobOutcome outcome = agent.run(input, ctx);
            if (record.status() == JobRecord.Status.CANCELLED) {
                emitJobError(record, JobError.CANCELLED, ErrorCode.CANCELLED, "cancelled");
                return;
            }
            if (record.status() == JobRecord.Status.ERROR) {
                return;
            }
            switch (outcome) {
                case JobOutcome.Success s -> {
                    record.transitionTo(JobRecord.Status.SUCCESS);
                    JobResult result = new JobResult(
                            JobResult.SUCCESS, s.resultId(), s.resultSize(),
                            s.inline(), s.summary());
                    sendJobMessage(record, Message.Type.JOB_RESULT, result, nextSeq());
                }
                case JobOutcome.Failure f -> {
                    record.transitionTo(JobRecord.Status.ERROR);
                    emitJobError(record, JobError.ERROR, f.code(), f.message());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // If the watchdog or an external cancel already transitioned this
            // record to a terminal state, do not emit a competing error.
            if (record.transitionTo(JobRecord.Status.CANCELLED)) {
                emitJobError(record, JobError.CANCELLED, ErrorCode.CANCELLED, "interrupted");
            }
        } catch (dev.arcp.core.error.LeaseExpiredException e) {
            record.transitionTo(JobRecord.Status.ERROR);
            emitJobError(record, JobError.ERROR, ErrorCode.LEASE_EXPIRED, e.getMessage());
        } catch (dev.arcp.core.error.BudgetExhaustedException e) {
            record.transitionTo(JobRecord.Status.ERROR);
            emitJobError(record, JobError.ERROR, ErrorCode.BUDGET_EXHAUSTED, e.getMessage());
        } catch (Exception e) {
            record.transitionTo(JobRecord.Status.ERROR);
            emitJobError(record, JobError.ERROR, ErrorCode.INTERNAL_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            ScheduledFuture<?> w = record.expiryWatchdog();
            if (w != null) {
                w.cancel(false);
            }
        }
    }

    private void handleCancel(Envelope envelope, JobCancel cancel) {
        JobId jobId = envelope.jobId();
        if (jobId == null) {
            return;
        }
        JobRecord rec = jobs.get(jobId);
        if (rec == null) {
            return;
        }
        if (!rec.principal().equals(principal)) {
            return; // §7.6: only the submitter may cancel
        }
        if (rec.transitionTo(JobRecord.Status.CANCELLED)) {
            var w = rec.worker();
            if (w != null) {
                w.cancel(true);
            }
            emitJobError(rec, JobError.CANCELLED, ErrorCode.CANCELLED,
                    cancel.reason() != null ? cancel.reason() : "cancelled");
        }
    }

    private void handleSubscribe(JobSubscribe sub) {
        if (!negotiated.contains(Feature.SUBSCRIBE)) {
            return;
        }
        JobRecord rec = jobs.get(sub.jobId());
        if (rec == null || !rec.principal().equals(principal)) {
            sendJobErrorTopLevel(null, ErrorCode.JOB_NOT_FOUND,
                    "job not found or not visible: " + sub.jobId());
            return;
        }
        rec.subscribers().add(new JobRecord.Subscriber(this, rec.jobId()));
        boolean wantHistory = Boolean.TRUE.equals(sub.history());
        long subscribedFrom = sub.fromEventSeq() != null ? sub.fromEventSeq() : 0;
        long now = eventSeq.get();
        JobSubscribed response = new JobSubscribed(
                rec.jobId(), rec.status().wire(), rec.resolvedAgent(), rec.lease(),
                null, rec.traceId(), now, wantHistory);
        send(Message.Type.JOB_SUBSCRIBED, response, sessionId, rec.traceId(), rec.jobId(), null);

        if (wantHistory) {
            for (Envelope replay : resumeBuffer.since(subscribedFrom)) {
                JobId jid = replay.jobId();
                if (rec.jobId().equals(jid)) {
                    try {
                        transport.send(replay);
                    } catch (RuntimeException e) {
                        log.warn("replay send failed: {}", e.toString());
                        return;
                    }
                }
            }
        }
    }

    private void handleUnsubscribe(JobUnsubscribe unsub) {
        JobRecord rec = jobs.get(unsub.jobId());
        if (rec != null) {
            rec.subscribers().removeIf(s -> s.session() == this);
        }
    }

    // ---------------------------------------------------------- emission

    private void emitJobEvent(JobRecord record, EventBody body) {
        long seq = nextSeq();
        record.setLastEventSeq(seq);
        JobEvent event = new JobEvent(body.kind().wire(), runtime.clock().instant(),
                mapper.valueToTree(body));
        sendJobMessage(record, Message.Type.JOB_EVENT, event, seq);
        for (JobRecord.Subscriber sub : record.subscribers()) {
            if (sub.session() != this) {
                sub.session().sendJobMessage(record, Message.Type.JOB_EVENT, event, seq);
            }
        }
    }

    private void emitJobError(
            JobRecord record, String finalStatus, ErrorCode code, String message) {
        JobError err = JobError.fromJson(finalStatus, code, message, null, null);
        sendJobMessage(record, Message.Type.JOB_ERROR, err, nextSeq());
    }

    private void sendJobErrorTopLevel(
            @Nullable Envelope origin, ErrorCode code, String message) {
        JobError err = JobError.fromJson(JobError.ERROR, code, message, null, null);
        send(Message.Type.JOB_ERROR, err, sessionId,
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

    private void send(
            Message.Type type,
            Message payload,
            @Nullable SessionId sid,
            @Nullable TraceId tid,
            @Nullable JobId jid,
            @Nullable Long seq) {
        if (phase == Phase.CLOSED) {
            return;
        }
        ObjectNode payloadJson = Messages.encodePayload(mapper, payload);
        Envelope env = new Envelope(
                Envelope.VERSION,
                MessageId.generate(),
                type.wire(),
                sid,
                tid,
                jid,
                seq,
                payloadJson);
        if (seq != null) {
            resumeBuffer.record(env);
        }
        try {
            transport.send(env);
        } catch (RuntimeException e) {
            log.warn("send failed: {}", e.toString());
            shutdown("send failure");
        }
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
        return phase;
    }
}
