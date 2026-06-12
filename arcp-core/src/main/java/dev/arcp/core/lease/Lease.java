package dev.arcp.core.lease;

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
 * <p>Reserved namespaces (§9.2): {@code fs.read}, {@code fs.write}, {@code net.fetch}, {@code
 * tool.call}, {@code agent.delegate}, {@code cost.budget}.
 */
public final class Lease {

  private final Map<String, List<String>> capabilities;

  /**
   * Creates a lease from a namespace → pattern-list map. The map and its lists are defensively
   * copied; iteration order is preserved.
   *
   * @param capabilities namespace to pattern-list map
   */
  public Lease(Map<String, List<String>> capabilities) {
    Objects.requireNonNull(capabilities, "capabilities");
    this.capabilities =
        Collections.unmodifiableMap(
            capabilities.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        e -> List.copyOf(e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new)));
  }

  /**
   * Returns a lease granting no capabilities.
   *
   * @return the empty lease
   */
  public static Lease empty() {
    return new Lease(Map.of());
  }

  /**
   * Returns the full capability map, which is also the §9.2 wire form of the lease.
   *
   * @return immutable namespace → pattern-list map
   */
  @JsonValue
  public Map<String, List<String>> capabilities() {
    return capabilities;
  }

  /**
   * Returns the patterns granted under one namespace.
   *
   * @param namespace the capability namespace (e.g. {@code fs.read})
   * @return the granted patterns, empty when the namespace is not present
   */
  public List<String> patterns(String namespace) {
    return capabilities.getOrDefault(namespace, List.of());
  }

  /**
   * Parses the {@code cost.budget} entries (§9.6) into initial amounts per currency.
   *
   * @return currency → amount map, empty when no budget capability is present
   * @throws IllegalArgumentException if an entry is not of the form {@code CURRENCY:amount}
   */
  public Map<String, BigDecimal> budget() {
    return patterns("cost.budget").stream()
        .map(
            entry -> {
              int colon = entry.indexOf(':');
              if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalArgumentException("invalid cost.budget pattern: " + entry);
              }
              return Map.entry(
                  entry.substring(0, colon), new BigDecimal(entry.substring(colon + 1)));
            })
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, BigDecimal::add, LinkedHashMap::new));
  }

  @JsonCreator
  static Lease fromJson(Map<String, List<String>> wire) {
    return wire == null ? empty() : new Lease(wire);
  }

  /**
   * §9.4 subset check: every child capability must be covered by the parent. For pattern namespaces
   * a child pattern is covered when some parent pattern covers it; for {@code cost.budget} the
   * comparison is numeric — every child currency must be present in the parent and its amount must
   * not exceed the parent's amount (a child cannot grant more spend than the parent holds).
   *
   * @param child the candidate subset lease
   * @return {@code true} if every child capability is covered by this lease
   */
  public boolean contains(Lease child) {
    return child.capabilities.entrySet().stream()
        .allMatch(
            e -> {
              if ("cost.budget".equals(e.getKey())) {
                return budgetContains(child);
              }
              List<String> parent = capabilities.get(e.getKey());
              return parent != null
                  && e.getValue().stream()
                      .allMatch(
                          childPattern ->
                              parent.stream()
                                  .anyMatch(parentPattern -> covers(parentPattern, childPattern)));
            });
  }

  private boolean budgetContains(Lease child) {
    Map<String, BigDecimal> parentBudget = budget();
    Map<String, BigDecimal> childBudget = child.budget();
    for (Map.Entry<String, BigDecimal> entry : childBudget.entrySet()) {
      BigDecimal parentAmount = parentBudget.get(entry.getKey());
      if (parentAmount == null || entry.getValue().compareTo(parentAmount) > 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean covers(String parentPattern, String childPattern) {
    if (parentPattern.equals(childPattern) || "**".equals(parentPattern)) {
      return true;
    }
    if ("*".equals(parentPattern) && !childPattern.contains("/")) {
      return true;
    }
    if (parentPattern.endsWith("/**")) {
      String prefix = parentPattern.substring(0, parentPattern.length() - 2);
      return childPattern.startsWith(prefix);
    }
    if (parentPattern.endsWith("/*")) {
      String prefix = parentPattern.substring(0, parentPattern.length() - 1);
      if (!childPattern.startsWith(prefix)) {
        return false;
      }
      return !childPattern.substring(prefix.length()).contains("/");
    }
    return false;
  }

  /** Mutable accumulator building a {@link Lease} namespace by namespace. */
  public static final class Builder {
    private final Map<String, List<String>> caps = new LinkedHashMap<>();

    /** Creates a builder granting no capabilities. */
    public Builder() {}

    /**
     * Grants patterns under a namespace, appending to any patterns already granted for it.
     *
     * @param namespace the capability namespace (e.g. {@code net.fetch})
     * @param patterns the §9.2 patterns to grant
     * @return this builder
     */
    public Builder allow(String namespace, String... patterns) {
      List<String> existing = caps.getOrDefault(namespace, new ArrayList<>());
      List<String> merged = new ArrayList<>(existing);
      Collections.addAll(merged, patterns);
      caps.put(namespace, merged);
      return this;
    }

    /**
     * Builds the immutable lease.
     *
     * @return the lease
     */
    public Lease build() {
      return new Lease(caps);
    }
  }

  /**
   * Creates a new {@link Builder}.
   *
   * @return an empty builder
   */
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
