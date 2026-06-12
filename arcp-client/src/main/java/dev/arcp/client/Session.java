package dev.arcp.client;

import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.ids.SessionId;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of a successfully handshaken ARCP session, built from the {@code session.welcome}
 * payload (§6.2). Collection components are defensively copied into immutable views so callers
 * cannot mutate the session after construction.
 *
 * @param sessionId the runtime-assigned session identifier echoed on every envelope
 * @param negotiatedFeatures the effective feature set, i.e. the intersection of the features
 *     requested in {@code session.hello} and those granted in {@code session.welcome} (§6.2)
 * @param resumeToken token presented in {@code session.resume} to reattach after a transport drop
 *     (§6.3), or {@code null} if the runtime did not offer resumption
 * @param heartbeatInterval the negotiated {@code heartbeat_interval_sec} (§6.4) as a {@link
 *     Duration}, or {@code null} if heartbeats were not negotiated
 * @param availableAgents the runtime's agent inventory advertised in {@code session.welcome},
 *     including version information (§6.2)
 */
public record Session(
    SessionId sessionId,
    Set<Feature> negotiatedFeatures,
    @Nullable String resumeToken,
    @Nullable Duration heartbeatInterval,
    List<AgentDescriptor> availableAgents) {

  /**
   * Canonical constructor; copies {@code negotiatedFeatures} and {@code availableAgents} into
   * immutable views.
   */
  public Session {
    negotiatedFeatures = Set.copyOf(negotiatedFeatures);
    availableAgents = List.copyOf(availableAgents);
  }
}
