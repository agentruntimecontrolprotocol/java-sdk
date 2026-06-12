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
 * Client/runtime capability advertisement carried on session.hello and session.welcome. Unknown
 * feature strings are ignored and dropped during decoding.
 *
 * @param encodings supported payload encodings; defaults to {@code ["json"]}
 * @param features advertised optional features (§6.2)
 * @param agents §7.5 agent inventory, or {@code null} when not advertised
 */
public record Capabilities(
    List<String> encodings, Set<Feature> features, @Nullable List<AgentDescriptor> agents) {

  /** Canonical constructor applying defaults and defensive copies. */
  public Capabilities {
    encodings = encodings == null ? List.of("json") : List.copyOf(encodings);
    features = features == null ? EnumSet.noneOf(Feature.class) : Set.copyOf(features);
    agents = agents == null ? null : List.copyOf(agents);
  }

  /**
   * Creates a JSON-only capability set advertising the given features and no agent inventory.
   *
   * @param features the optional features to advertise
   * @return the capability set
   */
  public static Capabilities of(Set<Feature> features) {
    return new Capabilities(List.of("json"), features, null);
  }

  @JsonCreator
  static Capabilities fromJson(
      @JsonProperty("encodings") @Nullable List<String> encodings,
      @JsonProperty("features") @Nullable List<String> features,
      @JsonProperty("agents") @Nullable List<AgentDescriptor> agents) {
    Set<Feature> parsed =
        features == null
            ? EnumSet.noneOf(Feature.class)
            : features.stream()
                .flatMap(w -> Feature.fromWire(w).stream())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Feature.class)));
    return new Capabilities(encodings == null ? List.of("json") : encodings, parsed, agents);
  }

  /**
   * Returns the features as sorted wire strings for serializing the {@code features} array.
   *
   * @return the sorted wire feature strings
   */
  @JsonProperty("features")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public List<String> featuresWire() {
    return features.stream().map(Feature::wire).sorted().toList();
  }

  /**
   * Computes the effective feature set per §6.2: the intersection of {@code session.hello} and
   * {@code session.welcome} features. Either peer MUST NOT use a feature outside it.
   *
   * @param a one peer's feature set
   * @param b the other peer's feature set
   * @return the intersection
   */
  public static Set<Feature> intersect(Set<Feature> a, Set<Feature> b) {
    Set<Feature> out = new HashSet<>(a);
    out.retainAll(b);
    return EnumSet.copyOf(out.isEmpty() ? EnumSet.noneOf(Feature.class) : out);
  }
}
