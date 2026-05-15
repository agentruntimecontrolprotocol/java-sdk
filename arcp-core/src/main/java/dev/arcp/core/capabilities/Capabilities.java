package dev.arcp.core.capabilities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Client/runtime capability advertisement carried on session.hello and session.welcome.
 * Unknown feature strings are preserved on the wire as ignored but round-trip-safe.
 */
public record Capabilities(
        List<String> encodings,
        Set<Feature> features,
        @Nullable List<AgentDescriptor> agents) {

    public Capabilities {
        encodings = encodings == null ? List.of("json") : List.copyOf(encodings);
        features = features == null ? EnumSet.noneOf(Feature.class) : Set.copyOf(features);
        agents = agents == null ? null : List.copyOf(agents);
    }

    public static Capabilities of(Set<Feature> features) {
        return new Capabilities(List.of("json"), features, null);
    }

    @JsonCreator
    static Capabilities fromJson(
            @JsonProperty("encodings") @Nullable List<String> encodings,
            @JsonProperty("features") @Nullable List<String> features,
            @JsonProperty("agents") @Nullable List<AgentDescriptor> agents) {
        Set<Feature> parsed = features == null
                ? EnumSet.noneOf(Feature.class)
                : features.stream()
                        .flatMap(w -> Feature.fromWire(w).stream())
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Feature.class)));
        return new Capabilities(
                encodings == null ? List.of("json") : encodings, parsed, agents);
    }

    @JsonProperty("features")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> featuresWire() {
        return features.stream().map(Feature::wire).sorted().toList();
    }

    public static Set<Feature> intersect(Set<Feature> a, Set<Feature> b) {
        Set<Feature> out = new HashSet<>(a);
        out.retainAll(b);
        return EnumSet.copyOf(out.isEmpty() ? EnumSet.noneOf(Feature.class) : out);
    }
}
