package dev.arcp.core.lease;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Capability bag: namespace → list of pattern strings.
 *
 * <p>Reserved namespaces (§9.2): {@code fs.read}, {@code fs.write}, {@code net.fetch},
 * {@code tool.call}, {@code agent.delegate}, {@code cost.budget}.
 */
public final class Lease {

    private final Map<String, List<String>> capabilities;

    public Lease(Map<String, List<String>> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        this.capabilities = Collections.unmodifiableMap(capabilities.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> List.copyOf(e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new)));
    }

    public static Lease empty() {
        return new Lease(Map.of());
    }

    @JsonValue
    public Map<String, List<String>> capabilities() {
        return capabilities;
    }

    public List<String> patterns(String namespace) {
        return capabilities.getOrDefault(namespace, List.of());
    }

    /** Parsed cost.budget initial amounts per currency. */
    public Map<String, BigDecimal> budget() {
        return patterns("cost.budget").stream()
                .map(entry -> {
                    int colon = entry.indexOf(':');
                    if (colon <= 0 || colon == entry.length() - 1) {
                        throw new IllegalArgumentException("invalid cost.budget pattern: " + entry);
                    }
                    return Map.entry(
                            entry.substring(0, colon),
                            new BigDecimal(entry.substring(colon + 1)));
                })
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        BigDecimal::add,
                        LinkedHashMap::new));
    }

    @JsonCreator
    static Lease fromJson(Map<String, List<String>> wire) {
        return wire == null ? empty() : new Lease(wire);
    }

    /** §9.4 subset check: every key/pattern in {@code child} appears in {@code this}. */
    public boolean contains(Lease child) {
        return child.capabilities.entrySet().stream().allMatch(e -> {
            List<String> parent = capabilities.get(e.getKey());
            return parent != null && parent.containsAll(e.getValue());
        });
    }

    public static final class Builder {
        private final Map<String, List<String>> caps = new LinkedHashMap<>();

        public Builder allow(String namespace, String... patterns) {
            List<String> existing = caps.getOrDefault(namespace, new ArrayList<>());
            List<String> merged = new ArrayList<>(existing);
            Collections.addAll(merged, patterns);
            caps.put(namespace, merged);
            return this;
        }

        public Lease build() {
            return new Lease(caps);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Lease other && capabilities.equals(other.capabilities);
    }

    @Override
    public int hashCode() {
        return capabilities.hashCode();
    }

    @Override
    public String toString() {
        return "Lease" + capabilities;
    }
}
