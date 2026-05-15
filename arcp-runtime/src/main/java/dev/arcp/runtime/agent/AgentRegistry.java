package dev.arcp.runtime.agent;

import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.error.AgentNotAvailableException;
import dev.arcp.core.error.AgentVersionNotAvailableException;
import java.util.ArrayList;
import java.util.Collections;
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
            for (Entry e : versions) {
                if (e.version.equals(chosen)) {
                    return new Resolved(ref.name(), e.version, e.agent);
                }
            }
            Entry fallback = versions.get(0);
            return new Resolved(ref.name(), fallback.version, fallback.agent);
        }
        for (Entry e : versions) {
            if (e.version.equals(ref.version())) {
                return new Resolved(ref.name(), e.version, e.agent);
            }
        }
        throw new AgentVersionNotAvailableException(
                ref.name() + "@" + ref.version() + " not registered");
    }

    public synchronized List<AgentDescriptor> describe() {
        // Sorted for stable wire output.
        Map<String, List<Entry>> sorted = new TreeMap<>(byName);
        List<AgentDescriptor> out = new ArrayList<>(sorted.size());
        for (var entry : sorted.entrySet()) {
            List<String> versions = new ArrayList<>(entry.getValue().size());
            for (Entry e : entry.getValue()) {
                versions.add(e.version);
            }
            Collections.sort(versions);
            out.add(new AgentDescriptor(entry.getKey(), versions, defaults.get(entry.getKey())));
        }
        return out;
    }

    public record Resolved(String name, String version, Agent agent) {
        public String wire() {
            return name + "@" + version;
        }
    }
}
