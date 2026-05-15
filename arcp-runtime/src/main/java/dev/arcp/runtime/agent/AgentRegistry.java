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

    public synchronized AgentRegistry register(String name, String version, Agent agent) {
        byName.computeIfAbsent(name, k -> new ArrayList<>()).add(new Entry(version, agent));
        defaults.putIfAbsent(name, version);
        return this;
    }

    public synchronized AgentRegistry setDefault(String name, String version) {
        defaults.put(name, version);
        return this;
    }

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
                    .orElseGet(() -> {
                        Entry fallback = versions.get(0);
                        return new Resolved(ref.name(), fallback.version, fallback.agent);
                    });
        }
        Entry match = versions.stream()
                .filter(e -> e.version.equals(ref.version()))
                .findFirst()
                .orElseThrow(() -> new AgentVersionNotAvailableException(
                        ref.name() + "@" + ref.version() + " not registered"));
        return new Resolved(ref.name(), match.version, match.agent);
    }

    public synchronized List<AgentDescriptor> describe() {
        // Sorted for stable wire output.
        return new TreeMap<>(byName).entrySet().stream()
                .map(entry -> new AgentDescriptor(
                        entry.getKey(),
                        entry.getValue().stream().map(Entry::version).sorted().toList(),
                        defaults.get(entry.getKey())))
                .toList();
    }

    public record Resolved(String name, String version, Agent agent) {
        public String wire() {
            return name + "@" + version;
        }
    }
}
