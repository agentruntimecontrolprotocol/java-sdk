package dev.arcp.runtime.agent;

import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.error.AgentNotAvailableException;
import dev.arcp.core.error.AgentVersionNotAvailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Holds registered agent versions and resolves {@link AgentRef} per §7.5. */
public final class AgentRegistry {

  private record Entry(String version, Agent agent) {}

  private final Map<String, List<Entry>> byName = new LinkedHashMap<>();
  private final Map<String, String> defaults = new LinkedHashMap<>();

  /** Creates an empty registry. */
  public AgentRegistry() {}

  /**
   * Registers a handler for {@code name} at {@code version}. The first version registered under a
   * name becomes that name's default until {@link #setDefault} overrides it.
   *
   * @param name the agent name as it appears in {@code job.submit.payload.agent}
   * @param version the version string this handler implements (opaque per §7.5)
   * @param agent the handler invoked for jobs resolved to this version
   * @return this registry
   */
  public synchronized AgentRegistry register(String name, String version, Agent agent) {
    byName.computeIfAbsent(name, k -> new ArrayList<>()).add(new Entry(version, agent));
    defaults.putIfAbsent(name, version);
    return this;
  }

  /**
   * Sets the version a bare {@code name} (no {@code @version} suffix) resolves to (§7.5).
   *
   * @param name the agent name
   * @param version the version advertised as {@code default} in {@code session.welcome}
   * @return this registry
   */
  public synchronized AgentRegistry setDefault(String name, String version) {
    defaults.put(name, version);
    return this;
  }

  /**
   * Resolves an {@link AgentRef} to a registered handler per §7.5: a bare name resolves to the
   * default version (falling back to the first registered version), while {@code name@version}
   * requires an exact match.
   *
   * @param ref the agent reference from {@code job.submit}
   * @return the resolved name, version, and handler
   * @throws AgentNotAvailableException if no version of {@code ref.name()} is registered
   * @throws AgentVersionNotAvailableException if the explicitly requested version is not registered
   *     ({@code AGENT_VERSION_NOT_AVAILABLE}, §12)
   */
  public synchronized Resolved resolve(AgentRef ref)
      throws AgentNotAvailableException, AgentVersionNotAvailableException {
    List<Entry> versions = byName.get(ref.name());
    if (versions == null || versions.isEmpty()) {
      throw new AgentNotAvailableException("agent not registered: " + ref.name());
    }
    if (ref.version() == null) {
      String chosen = defaults.get(ref.name());
      return versions.stream()
          .filter(e -> e.version.equals(chosen))
          .findFirst()
          .map(e -> new Resolved(ref.name(), e.version, e.agent))
          .orElseGet(
              () -> {
                Entry fallback = versions.get(0);
                return new Resolved(ref.name(), fallback.version, fallback.agent);
              });
    }
    Entry match =
        versions.stream()
            .filter(e -> e.version.equals(ref.version()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AgentVersionNotAvailableException(
                        ref.name() + "@" + ref.version() + " not registered"));
    return new Resolved(ref.name(), match.version, match.agent);
  }

  /**
   * Returns the agent inventory advertised in {@code session.welcome} (§6.2), sorted by name for
   * stable wire output.
   *
   * @return one descriptor per registered agent name, with its versions and default
   */
  public synchronized List<AgentDescriptor> describe() {
    // Sorted for stable wire output.
    return new TreeMap<>(byName)
        .entrySet().stream()
            .map(
                entry ->
                    new AgentDescriptor(
                        entry.getKey(),
                        entry.getValue().stream().map(Entry::version).sorted().toList(),
                        defaults.get(entry.getKey())))
            .toList();
  }

  /**
   * Outcome of {@link #resolve}: the agent identity a job is pinned to for its lifetime (§7.5).
   *
   * @param name the agent name
   * @param version the resolved version string
   * @param agent the handler registered for that version
   */
  public record Resolved(String name, String version, Agent agent) {
    /**
     * Returns the {@code name@version} form reported in {@code job.accepted} and listings (§7.5).
     *
     * @return the wire representation of the resolved agent
     */
    public String wire() {
      return name + "@" + version;
    }
  }
}
