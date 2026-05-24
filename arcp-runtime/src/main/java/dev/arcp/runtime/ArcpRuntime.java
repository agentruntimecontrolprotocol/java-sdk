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
              r ->
                  Thread.ofPlatform()
                      .name("arcp-runtime-scheduler", 0)
                      .daemon(true)
                      .unstarted(r));
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

  public static Builder builder() {
    return new Builder();
  }

  /** Attach a transport; the returned handle is closed on session.bye or transport close. */
  public SessionLoop accept(Transport transport) {
    SessionLoop loop = new SessionLoop(this, transport);
    loop.start();
    // Always key by the stable pending id, not idOrPending() — the latter flips to the
    // real session id after handshake and would leave the map keyed by a now-orphaned
    // string, so removeSession would never find the entry (#23).
    sessions.put(loop.pendingKey(), loop);
    return loop;
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  public AgentRegistry agents() {
    return agents;
  }

  public BearerVerifier verifier() {
    return verifier;
  }

  public Set<Feature> advertised() {
    return advertised;
  }

  public int heartbeatIntervalSec() {
    return heartbeatIntervalSec;
  }

  public int resumeWindowSec() {
    return resumeWindowSec;
  }

  public int resumeBufferCapacity() {
    return resumeBufferCapacity;
  }

  public Clock clock() {
    return clock;
  }

  public ExecutorService workerPool() {
    return workerPool;
  }

  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  public String runtimeName() {
    return runtimeName;
  }

  public String runtimeVersion() {
    return runtimeVersion;
  }

  public IdempotencyStore idempotency() {
    return idempotency;
  }

  public CredentialProvisioner credentialProvisioner() {
    return credentialProvisioner;
  }

  public CredentialRevocationStore credentialRevocationStore() {
    return credentialRevocationStore;
  }

  public void registerJob(JobRecord job) {
    jobs.put(job.jobId(), job);
  }

  public @Nullable JobRecord job(JobId jobId) {
    return jobs.get(jobId);
  }

  public void removeJob(JobId jobId) {
    jobs.remove(jobId);
  }

  public Collection<JobRecord> jobs() {
    return jobs.values();
  }

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
    sessions.clear();
    idempotency.close();
    if (ownedScheduler) {
      scheduler.shutdownNow();
    }
    if (ownedWorkerPool) {
      workerPool.shutdown();
    }
  }

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

    private static Set<Feature> defaultFeatures() {
      EnumSet<Feature> features = EnumSet.allOf(Feature.class);
      features.remove(Feature.MODEL_USE);
      features.remove(Feature.PROVISIONED_CREDENTIALS);
      return features;
    }

    public Builder agent(String name, String version, Agent agent) {
      agents.register(name, version, agent);
      return this;
    }

    public Builder agents(AgentRegistry registry) {
      this.agents = registry;
      return this;
    }

    public Builder verifier(BearerVerifier v) {
      this.verifier = v;
      return this;
    }

    public Builder features(Set<Feature> features) {
      this.advertised = safeFeatureCopy(features);
      this.featuresConfigured = true;
      return this;
    }

    public Builder heartbeatIntervalSec(int sec) {
      this.heartbeatIntervalSec = sec;
      return this;
    }

    public Builder resumeWindowSec(int sec) {
      this.resumeWindowSec = sec;
      return this;
    }

    public Builder resumeBufferCapacity(int cap) {
      this.resumeBufferCapacity = cap;
      return this;
    }

    public Builder clock(Clock c) {
      this.clock = c;
      return this;
    }

    public Builder mapper(ObjectMapper m) {
      this.mapper = m;
      return this;
    }

    public Builder workerPool(ExecutorService e) {
      this.workerPool = e;
      return this;
    }

    public Builder scheduler(ScheduledExecutorService s) {
      this.scheduler = s;
      return this;
    }

    public Builder runtimeName(String n) {
      this.runtimeName = n;
      return this;
    }

    public Builder runtimeVersion(String v) {
      this.runtimeVersion = v;
      return this;
    }

    public Builder idempotencyTtl(Duration ttl) {
      this.idempotencyTtl = ttl;
      return this;
    }

    public Builder credentialProvisioner(CredentialProvisioner provisioner) {
      this.credentialProvisioner = provisioner;
      return this;
    }

    public Builder credentialRevocationStore(CredentialRevocationStore store) {
      this.credentialRevocationStore = store;
      return this;
    }

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
