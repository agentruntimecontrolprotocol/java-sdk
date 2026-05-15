package dev.arcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * ARCP client over a {@link Transport}. Owns the transport for the lifetime of
 * the session; closing the client closes the transport.
 */
public final class ArcpClient implements AutoCloseable, Flow.Subscriber<Envelope> {

    private static final Logger log = LoggerFactory.getLogger(ArcpClient.class);

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
    private final AtomicLong lastSeenSeq = new AtomicLong(-1);
    private final AtomicLong lastAckedSeq = new AtomicLong(-1);
    private final AtomicLong lastInboundMillis = new AtomicLong(System.currentTimeMillis());
    private @Nullable ScheduledFuture<?> ackTick;
    private @Nullable ScheduledFuture<?> heartbeatWatchdog;
    private final ConcurrentHashMap<JobId, SubmissionPublisher<EventBody>> liveSubscribers =
            new ConcurrentHashMap<>();
    @SuppressWarnings("unused")
    private Flow.@Nullable Subscription subscription;
    private volatile @Nullable SessionId sessionId;
    private volatile @Nullable Session session;
    private volatile boolean closed;

    private ArcpClient(Builder b) {
        this.transport = Objects.requireNonNull(b.transport, "transport");
        this.mapper = b.mapper != null ? b.mapper : ArcpMapper.shared();
        this.info = b.info;
        this.auth = b.auth;
        this.requestedFeatures = EnumSet.copyOf(b.features);
        this.autoAck = b.autoAck;
        this.ackInterval = b.ackInterval;
        this.scheduler = b.scheduler != null ? b.scheduler
                : Executors.newScheduledThreadPool(1, r -> Thread.ofPlatform()
                        .name("arcp-client-scheduler", 0).daemon(true).unstarted(r));
    }

    public static Builder builder(Transport transport) {
        return new Builder(transport);
    }

    /** Send hello and return a future completing with the negotiated {@link Session}. */
    public CompletableFuture<Session> connect() {
        transport.incoming().subscribe(this);
        SessionHello hello = new SessionHello(
                info, auth, Capabilities.of(requestedFeatures), null, null);
        send(Message.Type.SESSION_HELLO, hello, null, null, null, null);
        return sessionFuture;
    }

    public Session connect(Duration timeout) throws InterruptedException, TimeoutException {
        try {
            return connect().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("connect failed", e.getCause());
        }
    }

    public JobHandle submit(JobSubmit submit) {
        return submit(submit, null);
    }

    public JobHandle submit(JobSubmit submit, @Nullable TraceId traceId) {
        Outstanding o = new Outstanding();
        // Pre-register the outstanding job under a request-id keyed slot so that
        // when JobAccepted arrives we associate the job_id.
        MessageId requestId = MessageId.generate();
        pendingSubmits.put(requestId, o);
        send(Message.Type.JOB_SUBMIT, submit, sessionId, traceId, null, null, requestId);
        return o.handleFuture.join();
    }

    public Page<JobSummary> listJobs(@Nullable JobFilter filter) {
        SessionListJobs req = new SessionListJobs(filter, null, null);
        MessageId reqId = MessageId.generate();
        CompletableFuture<SessionJobs> fut = new CompletableFuture<>();
        listRequests.put(reqId, fut);
        send(Message.Type.SESSION_LIST_JOBS, req, sessionId, null, null, null, reqId);
        try {
            SessionJobs response = fut.get(10, TimeUnit.SECONDS);
            return new Page<>(response.jobs(), response.nextCursor());
        } catch (java.util.concurrent.ExecutionException
                | InterruptedException
                | TimeoutException e) {
            throw new RuntimeException("list_jobs failed", e);
        }
    }

    public Flow.Publisher<EventBody> subscribe(JobId jobId, SubscribeOptions options) {
        SubmissionPublisher<EventBody> pub = liveSubscribers.computeIfAbsent(
                jobId, k -> new SubmissionPublisher<>(
                        Executors.newVirtualThreadPerTaskExecutor(), 1024));
        JobSubscribe sub = new JobSubscribe(
                jobId, options.history() ? options.fromEventSeq() : null, options.history());
        send(Message.Type.JOB_SUBSCRIBE, sub, sessionId, null, jobId, null);
        return pub;
    }

    public void ack(long lastProcessedSeq) {
        send(Message.Type.SESSION_ACK, new SessionAck(lastProcessedSeq),
                sessionId, null, null, null);
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
        try {
            transport.close();
        } catch (RuntimeException ignored) {
            // best-effort close
        }
        scheduler.shutdownNow();
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
        try {
            dispatch(envelope);
        } catch (RuntimeException e) {
            log.warn("client dispatch error for {}: {}", envelope.type(), e.toString());
        }
    }

    @Override
    public void onError(Throwable throwable) {
        sessionFuture.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        if (!sessionFuture.isDone()) {
            sessionFuture.completeExceptionally(
                    new IllegalStateException("transport closed before welcome"));
        }
    }

    // ---------------------------------------------------------- inbound

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
            case JobSubscribed ignored -> { /* signal */ }
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
        Session s = new Session(
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
            ackTick = scheduler.scheduleAtFixedRate(
                    this::maybeAck, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
        // §6.4 heartbeat watchdog: detect two missed intervals.
        if (s.heartbeatInterval() != null && s.negotiatedFeatures().contains(Feature.HEARTBEAT)) {
            long intervalMs = s.heartbeatInterval().toMillis();
            heartbeatWatchdog = scheduler.scheduleAtFixedRate(
                    () -> watchHeartbeat(intervalMs),
                    intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        sessionFuture.complete(s);
    }

    private void maybeAck() {
        long current = lastSeenSeq.get();
        long acked = lastAckedSeq.get();
        if (current > acked) {
            lastAckedSeq.set(current);
            try {
                send(Message.Type.SESSION_ACK, new SessionAck(current),
                        sessionId, null, null, null);
            } catch (RuntimeException e) {
                log.warn("ack emit failed: {}", e.toString());
            }
        }
    }

    private void watchHeartbeat(long intervalMs) {
        long elapsed = System.currentTimeMillis() - lastInboundMillis.get();
        if (elapsed > intervalMs * 2) {
            log.info("client observed heartbeat loss; closing session");
            close();
        }
    }

    private final ConcurrentHashMap<MessageId, Outstanding> pendingSubmits =
            new ConcurrentHashMap<>();

    private void handleAccepted(Envelope envelope, JobAccepted accepted) {
        // We associate by traversing pending submits in insertion order; the
        // runtime guarantees ordering per-session, so the oldest pending submit
        // is the one being acknowledged.
        MessageId match = pendingSubmits.keySet().stream().findFirst().orElse(null);
        if (match == null) {
            return;
        }
        Outstanding o = pendingSubmits.remove(match);
        if (o == null) {
            return;
        }
        o.jobId = accepted.jobId();
        outstanding.put(accepted.jobId(), o);
        o.handleFuture.complete(new ClientJobHandle(accepted, o));
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
        if (o == null) {
            return;
        }
        o.events.close();
        o.resultFuture.complete(result);
    }

    private void handleError(Envelope envelope, JobError err) {
        JobId jid = envelope.jobId();
        Outstanding o = jid != null ? outstanding.remove(jid) : null;
        if (o == null) {
            // Top-level (unassigned) error: drop the oldest pending submit.
            MessageId first = pendingSubmits.keySet().stream().findFirst().orElse(null);
            if (first != null) {
                Outstanding pending = pendingSubmits.remove(first);
                if (pending != null) {
                    ArcpException ex = ArcpException.from(
                            ErrorPayload.of(err.code(), err.message()));
                    pending.handleFuture.completeExceptionally(ex);
                }
            }
            return;
        }
        o.events.close();
        ArcpException ex = ArcpException.from(ErrorPayload.of(err.code(), err.message()));
        o.resultFuture.completeExceptionally(ex);
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

    // ---------------------------------------------------------- send

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
        Envelope env = new Envelope(
                Envelope.VERSION, id, type.wire(), sid, tid, jid, seq, payloadJson);
        transport.send(env);
    }

    // ---------------------------------------------------------- internals

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
            send(Message.Type.JOB_CANCEL, new JobCancel("client cancel"),
                    sessionId, null, accepted.jobId(), null);
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
        private @Nullable ScheduledExecutorService scheduler;

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
            this.features = EnumSet.copyOf(features);
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

        public Builder scheduler(ScheduledExecutorService s) {
            this.scheduler = s;
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
        if (constraints != null && constraints.expiresAt() != null
                && !constraints.expiresAt().isAfter(java.time.Instant.now())) {
            throw new IllegalArgumentException("expires_at must be in the future");
        }
        return new JobSubmit(
                AgentRef.parse(agent), input, lease, constraints, idempotencyKey, maxRuntimeSec);
    }
}
