package dev.arcp.client;

import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.ids.SessionId;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of a successfully handshaken ARCP session. Collection components are defensively copied
 * into immutable views so callers cannot mutate the session after construction.
 */
public record Session(
    SessionId sessionId,
    Set<Feature> negotiatedFeatures,
    @Nullable String resumeToken,
    @Nullable Duration heartbeatInterval,
    List<AgentDescriptor> availableAgents) {

  public Session {
    negotiatedFeatures = Set.copyOf(negotiatedFeatures);
    availableAgents = List.copyOf(availableAgents);
  }
}
