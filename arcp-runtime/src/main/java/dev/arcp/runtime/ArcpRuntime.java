package dev.arcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.auth.BearerVerifier;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.transport.Transport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.runtime.agent.Agent;
import dev.arcp.runtime.agent.AgentRegistry;
import dev.arcp.runtime.credentials.CredentialProvisioner;
import dev.arcp.runtime.credentials.CredentialRevocationStore;
import dev.arcp.runtime.credentials.InMemoryCredentialRevocationStore;
import dev.arcp.runtime.credentials.NoopCredentialProvisioner;
import dev.arcp.runtime.idempotency.IdempotencyStore;
import dev.arcp.runtime.session.JobRecord;
import dev.arcp.runtime.session.SessionLoop;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;

/**
 * In-process ARCP runtime. Holds the agent registry, mapper, executor, and scheduler used to run
 * sessions; transports are {@linkplain #accept attached} one at a time.
 */
public final class ArcpRuntime implements AutoCloseable {

  private final ObjectMapper mapper;
  private final AgentRegistry agents;
  private final BearerVerifier verifier;
  private final Set<Feature> advertised;
  private final int heartbeatIntervalSec;
  private final int resumeWindowSec;
  private final int resumeBufferCapacity;
  private final Clock clock;
  private final ExecutorService workerPool;
  private final boolean ownedWorkerPool;
  private final ScheduledExecutorService scheduler;
  private final boolean ownedScheduler;
  private final String runtimeName;
  private final String runtimeVersion;
  private final IdempotencyStore idempotency;
  private final CredentialProvisioner credentialProvisioner;
  private final CredentialRevocationStore credentialRevocationStore;
  private final ConcurrentHashMap<String, SessionLoop> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<JobId, JobRecord> jobs = new ConcurrentHashMap<>();
  // §6.3 resume: sessions whose transport dropped unexpectedly are parked here by resume token for
  // the resume window so a reconnect can reattach to the same identity and in-flight jobs (#22).
  private final ConcurrentHashMap<String, SessionLoop> resumable = new ConcurrentHashMap<>();

  private ArcpRuntime(Builder b) {
    this.mapper = b.mapper != null ? b.mapper : ArcpMapper.shared();
    this.agents = Objects.requireNonNull(b.agents, "agents");
    this.verifier = b.verifier != null ? b.verifier : BearerVerifier.acceptAny();
    this.credentialProvisioner =
        b.credentialProvisioner != null
            ? b.credentialProvisioner
            : NoopCredentialProvisioner.INSTANCE;
    this.credentialRevocationStore =
        b.credentialRevocationStore != null
            ? b.credentialRevocationStore
            : new InMemoryCredentialRevocationStore();
    this.advertised = effectiveFeatures(b);
    this.heartbeatIntervalSec = b.heartbeatIntervalSec;
    this.resumeWindowSec = b.resumeWindowSec;
    this.resumeBufferCapacity = b.resumeBufferCapacity;
    this.clock = b.clock != null ? b.clock : Clock.systemUTC();
    if (b.workerPool != null) {
      this.workerPool = b.workerPool;
      this.ownedWorkerPool = false;
    } else {
      this.workerPool = Executors.newVirtualThreadPerTaskExecutor();
      this.ownedWorkerPool = true;
    }
    if (b.scheduler != null) {
      this.scheduler = b.scheduler;
      this.ownedScheduler = false;
    } else {
      this.scheduler =
          Executors.newScheduledThreadPool(
              1,
              r -> Thread.ofPlatform().name("arcp-runtime-scheduler", 0).daemon(true).unstarted(r));
      this.ownedScheduler = true;
    }
    this.runtimeName = b.runtimeName;
    this.runtimeVersion = b.runtimeVersion;
    this.idempotency =
        new IdempotencyStore(this.clock, b.idempotencyTtl, this.scheduler, Duration.ofMinutes(1));
  }

  private static Set<Feature> effectiveFeatures(Builder b) {
    EnumSet<Feature> features = safeFeatureCopy(b.advertised);
    boolean hasProvisioner =
        b.credentialProvisioner != null
            && b.credentialProvisioner != NoopCredentialProvisioner.INSTANCE;
    if (hasProvisioner && !b.featuresConfigured) {
      features.add(Feature.MODEL_USE);
      features.add(Feature.PROVISIONED_CREDENTIALS);
    }
    if (!hasProvisioner && !b.featuresConfigured) {
      features.remove(Feature.MODEL_USE);
      features.remove(Feature.PROVISIONED_CREDENTIALS);
    }
    return java.util.Collections.unmodifiableSet(features);
  }

  static EnumSet<Feature> safeFeatureCopy(Set<Feature> features) {
    if (features == null || features.isEmpty()) {
      return EnumSet.noneOf(Feature.class);
    }
    return EnumSet.copyOf(features);
  }

  /**
   * Creates a builder for assembling a runtime.
   *
   * @return a new {@link Builder} preloaded with default settings
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Attaches a transport; the returned handle is closed on {@code session.bye} or transport close.
   *
   * @param transport the connected transport to drive a session over
   * @return the session loop now serving {@code transport}
   */
  public SessionLoop accept(Transport transport) {
    SessionLoop loop = new SessionLoop(this, transport);
    // Insert before start() so that if the transport completes/errors synchronously during start
    // (e.g. an already-closed transport), shutdown -> removeSession finds and removes this entry
    // instead of racing a later put that would leave a zombie CLOSED loop in the map (#107).
    // Always key by the stable pending id, not idOrPending() — the latter flips to the
    // real session id after handshake and would leave the map keyed by a now-orphaned
    // string, so removeSession would never find the entry (#23).
    sessions.put(loop.pendingKey(), loop);
    loop.start();
    if (loop.phase() == SessionLoop.Phase.CLOSED) {
      removeSession(loop);
    }
    return loop;
  }

  /**
   * Returns the Jackson mapper used for ARCP wire I/O (§5).
   *
   * @return the configured {@link ObjectMapper}
   */
  public ObjectMapper mapper() {
    return mapper;
  }

  /**
   * Returns the registry of agents this runtime can resolve and run (§7.5).
   *
   * @return the agent registry
   */
  public AgentRegistry agents() {
    return agents;
  }

  /**
   * Returns the verifier applied to the bearer token in {@code session.hello} (§6.1).
   *
   * @return the bearer-token verifier
   */
  public BearerVerifier verifier() {
    return verifier;
  }

  /**
   * Returns the feature set advertised in {@code session.welcome} (§6.2).
   *
   * @return an unmodifiable set of advertised features
   */
  public Set<Feature> advertised() {
    return advertised;
  }

  /**
   * Returns the {@code heartbeat_interval_sec} advertised in {@code session.welcome} (§6.4).
   *
   * @return the heartbeat interval in seconds
   */
  public int heartbeatIntervalSec() {
    return heartbeatIntervalSec;
  }

  /**
   * Returns the {@code resume_window_sec} during which a dropped session may resume (§6.3).
   *
   * @return the resume window in seconds
   */
  public int resumeWindowSec() {
    return resumeWindowSec;
  }

  /**
   * Returns the per-session capacity of the outbound event buffer kept for §6.3 resume replay.
   *
   * @return the resume buffer capacity in envelopes
   */
  public int resumeBufferCapacity() {
    return resumeBufferCapacity;
  }

  /**
   * Returns the clock used for timestamps, idempotency TTLs, and lease-expiry decisions.
   *
   * @return the runtime clock
   */
  public Clock clock() {
    return clock;
  }

  /**
   * Returns the executor on which agent jobs execute.
   *
   * @return the worker pool
   */
  public ExecutorService workerPool() {
    return workerPool;
  }

  /**
   * Returns the scheduler used for heartbeat ticks, watchdogs, and background pruning.
   *
   * @return the shared scheduler
   */
  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  /**
   * Returns the runtime name reported in {@code session.welcome.payload.runtime} (§6.2).
   *
   * @return the runtime name
   */
  public String runtimeName() {
    return runtimeName;
  }

  /**
   * Returns the runtime version reported in {@code session.welcome.payload.runtime} (§6.2).
   *
   * @return the runtime version string
   */
  public String runtimeVersion() {
    return runtimeVersion;
  }

  /**
   * Returns the store backing {@code idempotency_key} deduplication (§7.2).
   *
   * @return the idempotency store
   */
  public IdempotencyStore idempotency() {
    return idempotency;
  }

  /**
   * Returns the provisioner that mints per-job upstream credentials (§9.8).
   *
   * @return the credential provisioner; a no-op instance when none was configured
   */
  public CredentialProvisioner credentialProvisioner() {
    return credentialProvisioner;
  }

  /**
   * Returns the store tracking issued credentials until their revocation succeeds (§9.8).
   *
   * @return the credential revocation store
   */
  public CredentialRevocationStore credentialRevocationStore() {
    return credentialRevocationStore;
  }

  /**
   * Registers an accepted job so it is visible to {@code session.list_jobs} (§6.6) and {@code
   * job.subscribe} (§7.6).
   *
   * @param job the record of the accepted job
   */
  public void registerJob(JobRecord job) {
    jobs.put(job.jobId(), job);
  }

  /**
   * Looks up a registered job by id.
   *
   * @param jobId the job id to look up
   * @return the job record, or {@code null} if no such job is registered
   */
  public @Nullable JobRecord job(JobId jobId) {
    return jobs.get(jobId);
  }

  /**
   * Removes a job from the registry.
   *
   * @param jobId the id of the job to remove
   */
  public void removeJob(JobId jobId) {
    jobs.remove(jobId);
  }

  /**
   * Returns a live view of every registered job, across all sessions.
   *
   * @return the registered job records
   */
  public Collection<JobRecord> jobs() {
    return jobs.values();
  }

  /**
   * Parks a session for resume, keyed by its resume token (§6.3, #22).
   *
   * @param resumeToken the token a reconnecting client must present in {@code session.resume}
   * @param loop the session loop to park
   */
  public void parkResumable(String resumeToken, SessionLoop loop) {
    resumable.put(resumeToken, loop);
  }

  /**
   * Atomically claims a parked session for resume (#22).
   *
   * @param resumeToken the token presented in {@code session.resume}
   * @return the parked session loop, or {@code null} if the token is unknown or expired (§6.3)
   */
  public @Nullable SessionLoop takeResumable(String resumeToken) {
    return resumable.remove(resumeToken);
  }

  /**
   * Removes a parked session only if it still maps to {@code loop} (#22).
   *
   * @param resumeToken the token the session was parked under
   * @param loop the session loop expected at that token
   */
  public void removeResumable(String resumeToken, SessionLoop loop) {
    resumable.remove(resumeToken, loop);
  }

  /**
   * Drops a closed session from the live-session map.
   *
   * @param loop the session loop to remove
   */
  public void removeSession(SessionLoop loop) {
    // Always remove via the pending key the loop was inserted under, since
    // idOrPending() flips to the real session id after handshake and would
    // otherwise leak the closed session in the map (#23).
    sessions.remove(loop.pendingKey(), loop);
    sessions.remove(loop.idOrPending(), loop);
  }

  @Override
  public void close() {
    for (SessionLoop loop : sessions.values()) {
      loop.shutdown("runtime closing");
    }
    for (SessionLoop loop : resumable.values()) {
      loop.shutdown("runtime closing");
    }
    resumable.clear();
    sessions.clear();
    idempotency.close();
    if (ownedScheduler) {
      scheduler.shutdownNow();
    }
    if (ownedWorkerPool) {
      workerPool.shutdown();
    }
  }

  /**
   * Fluent builder for {@link ArcpRuntime}. Every setting has a working default; the minimal useful
   * configuration registers at least one {@linkplain #agent agent}. Unless overridden, the runtime
   * accepts any bearer token, runs jobs on an owned virtual-thread executor, and advertises all
   * features except {@code model.use} and {@code provisioned_credentials} — those two are added
   * automatically when a real {@linkplain #credentialProvisioner provisioner} is configured (§9.8).
   */
  public static final class Builder {
    private @Nullable ObjectMapper mapper;
    private AgentRegistry agents = new AgentRegistry();
    private @Nullable BearerVerifier verifier;
    private Set<Feature> advertised = defaultFeatures();
    private boolean featuresConfigured;
    private int heartbeatIntervalSec = 30;
    private int resumeWindowSec = 600;
    private int resumeBufferCapacity = 1024;
    private @Nullable Clock clock;
    private @Nullable ExecutorService workerPool;
    private @Nullable ScheduledExecutorService scheduler;
    private String runtimeName = "arcp-runtime-java";
    private String runtimeVersion = "1.0.0";
    private Duration idempotencyTtl = Duration.ofHours(24);
    private @Nullable CredentialProvisioner credentialProvisioner;
    private @Nullable CredentialRevocationStore credentialRevocationStore;

    /** Creates a builder with all settings at their defaults. */
    public Builder() {}

    private static Set<Feature> defaultFeatures() {
      EnumSet<Feature> features = EnumSet.allOf(Feature.class);
      features.remove(Feature.MODEL_USE);
      features.remove(Feature.PROVISIONED_CREDENTIALS);
      return features;
    }

    /**
     * Registers an agent version with the runtime's registry (§7.5). The first version registered
     * under a name becomes that name's default.
     *
     * @param name the agent name as it appears in {@code job.submit.payload.agent}
     * @param version the version string this handler implements
     * @param agent the handler invoked for jobs resolved to this version
     * @return this builder
     */
    public Builder agent(String name, String version, Agent agent) {
      agents.register(name, version, agent);
      return this;
    }

    /**
     * Replaces the backing agent registry, discarding any agents registered so far.
     *
     * @param registry the registry to resolve agents from
     * @return this builder
     */
    public Builder agents(AgentRegistry registry) {
      this.agents = registry;
      return this;
    }

    /**
     * Sets the verifier applied to the bearer token in {@code session.hello} (§6.1). Defaults to
     * accepting any token.
     *
     * @param v the bearer-token verifier
     * @return this builder
     */
    public Builder verifier(BearerVerifier v) {
      this.verifier = v;
      return this;
    }

    /**
     * Sets the feature set advertised in {@code session.welcome} (§6.2) explicitly, disabling the
     * provisioner-driven defaulting of {@code model.use} and {@code provisioned_credentials}.
     *
     * @param features the features to advertise; copied defensively
     * @return this builder
     */
    public Builder features(Set<Feature> features) {
      this.advertised = safeFeatureCopy(features);
      this.featuresConfigured = true;
      return this;
    }

    /**
     * Sets the {@code heartbeat_interval_sec} advertised in {@code session.welcome} (§6.4).
     * Defaults to 30.
     *
     * @param sec the heartbeat interval in seconds
     * @return this builder
     */
    public Builder heartbeatIntervalSec(int sec) {
      this.heartbeatIntervalSec = sec;
      return this;
    }

    /**
     * Sets the {@code resume_window_sec} during which a dropped session may resume (§6.3). Defaults
     * to 600.
     *
     * @param sec the resume window in seconds
     * @return this builder
     */
    public Builder resumeWindowSec(int sec) {
      this.resumeWindowSec = sec;
      return this;
    }

    /**
     * Sets the per-session capacity of the outbound event buffer used for §6.3 resume replay.
     * Defaults to 1024 envelopes.
     *
     * @param cap the buffer capacity in envelopes
     * @return this builder
     */
    public Builder resumeBufferCapacity(int cap) {
      this.resumeBufferCapacity = cap;
      return this;
    }

    /**
     * Sets the clock used for timestamps, TTLs, and lease-expiry decisions; useful for
     * deterministic tests. Defaults to {@link Clock#systemUTC()}.
     *
     * @param c the clock to use
     * @return this builder
     */
    public Builder clock(Clock c) {
      this.clock = c;
      return this;
    }

    /**
     * Sets the Jackson mapper used for ARCP wire I/O (§5). Defaults to {@link ArcpMapper#shared()}.
     *
     * @param m the mapper to use
     * @return this builder
     */
    public Builder mapper(ObjectMapper m) {
      this.mapper = m;
      return this;
    }

    /**
     * Sets the executor on which agent jobs run. A supplied pool is not shut down by {@link
     * ArcpRuntime#close()}; by default the runtime owns (and shuts down) a virtual-thread-per-task
     * executor.
     *
     * @param e the worker pool
     * @return this builder
     */
    public Builder workerPool(ExecutorService e) {
      this.workerPool = e;
      return this;
    }

    /**
     * Sets the scheduler used for heartbeat ticks, watchdogs, and background pruning. A supplied
     * scheduler is not shut down by {@link ArcpRuntime#close()}; by default the runtime owns a
     * single-threaded daemon scheduler.
     *
     * @param s the scheduler to use
     * @return this builder
     */
    public Builder scheduler(ScheduledExecutorService s) {
      this.scheduler = s;
      return this;
    }

    /**
     * Sets the runtime name reported in {@code session.welcome.payload.runtime} (§6.2). Defaults to
     * {@code arcp-runtime-java}.
     *
     * @param n the runtime name
     * @return this builder
     */
    public Builder runtimeName(String n) {
      this.runtimeName = n;
      return this;
    }

    /**
     * Sets the runtime version reported in {@code session.welcome.payload.runtime} (§6.2).
     *
     * @param v the runtime version string
     * @return this builder
     */
    public Builder runtimeVersion(String v) {
      this.runtimeVersion = v;
      return this;
    }

    /**
     * Sets how long {@code idempotency_key} claims are retained (§7.2). Defaults to 24 hours.
     *
     * @param ttl the retention window for idempotency entries
     * @return this builder
     */
    public Builder idempotencyTtl(Duration ttl) {
      this.idempotencyTtl = ttl;
      return this;
    }

    /**
     * Sets the backend that mints and revokes per-job upstream credentials (§9.8). Unless {@link
     * #features} was called, configuring a real provisioner also advertises {@code model.use} and
     * {@code provisioned_credentials}.
     *
     * @param provisioner the credential provisioner
     * @return this builder
     */
    public Builder credentialProvisioner(CredentialProvisioner provisioner) {
      this.credentialProvisioner = provisioner;
      return this;
    }

    /**
     * Sets the store tracking issued credentials until their revocation succeeds (§9.8). Required
     * to be durable in production whenever {@code provisioned_credentials} is advertised; defaults
     * to an in-memory store.
     *
     * @param store the credential revocation store
     * @return this builder
     */
    public Builder credentialRevocationStore(CredentialRevocationStore store) {
      this.credentialRevocationStore = store;
      return this;
    }

    /**
     * Validates the configuration and creates the runtime.
     *
     * @return the configured runtime
     * @throws IllegalStateException if {@code provisioned_credentials} or {@code model.use} is
     *     advertised without a configured provisioner, or if {@code provisioned_credentials} is
     *     advertised without a revocation store (§9.8)
     */
    public ArcpRuntime build() {
      Set<Feature> effective = effectiveFeatures(this);
      boolean advertisesCredentials = effective.contains(Feature.PROVISIONED_CREDENTIALS);
      boolean hasProvisioner =
          credentialProvisioner != null
              && credentialProvisioner != NoopCredentialProvisioner.INSTANCE;
      if (advertisesCredentials && !hasProvisioner) {
        throw new IllegalStateException(
            "provisioned_credentials advertised without a configured provisioner");
      }
      if (effective.contains(Feature.MODEL_USE) && !hasProvisioner) {
        throw new IllegalStateException("model.use advertised without a configured provisioner");
      }
      if (advertisesCredentials && credentialRevocationStore == null) {
        throw new IllegalStateException(
            "provisioned_credentials advertised without a durable revocation path; "
                + "configure credentialRevocationStore(...) or remove "
                + "Feature.PROVISIONED_CREDENTIALS");
      }
      return new ArcpRuntime(this);
    }
  }
}
