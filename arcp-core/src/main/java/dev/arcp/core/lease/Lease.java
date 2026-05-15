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
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (var e : capabilities.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.capabilities = Collections.unmodifiableMap(copy);
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
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String entry : patterns("cost.budget")) {
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalArgumentException("invalid cost.budget pattern: " + entry);
            }
            String currency = entry.substring(0, colon);
            BigDecimal amount = new BigDecimal(entry.substring(colon + 1));
            out.merge(currency, amount, BigDecimal::add);
        }
        return out;
    }

    @JsonCreator
    static Lease fromJson(Map<String, List<String>> wire) {
        return wire == null ? empty() : new Lease(wire);
    }

    /** §9.4 subset check: every key/pattern in {@code child} appears in {@code this}. */
    public boolean contains(Lease child) {
        for (var e : child.capabilities.entrySet()) {
            List<String> parent = capabilities.get(e.getKey());
            if (parent == null) {
                return false;
            }
            for (String pat : e.getValue()) {
                if (!parent.contains(pat)) {
                    return false;
                }
            }
        }
        return true;
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
