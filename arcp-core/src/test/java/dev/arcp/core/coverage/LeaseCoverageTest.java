package dev.arcp.core.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.wire.ArcpMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Branch coverage for {@link Lease} glob matching, budget arithmetic, and identity. */
class LeaseCoverageTest {

  @ParameterizedTest(name = "covers({0}, {1}) == {2}")
  @CsvSource({
    "'/a/b',  '/a/b',   true",
    "'**',    '/x/y',   true",
    "'*',     'file',   true",
    "'*',     'd/file', false",
    "'/a/**', '/a/b/c', true",
    "'/a/**', '/b/c',   false",
    "'/a/*',  '/a/b',   true",
    "'/a/*',  '/a/b/c', false",
    "'/a/*',  '/b/x',   false",
    "'abc',   'def',    false",
  })
  void coversMatrix(String parentPattern, String childPattern, boolean expected) {
    Lease parent = Lease.builder().allow("fs.read", parentPattern).build();
    Lease child = Lease.builder().allow("fs.read", childPattern).build();
    assertThat(parent.contains(child)).isEqualTo(expected);
  }

  @Test
  void budgetParsesAmountsAndMergesDuplicateCurrencies() {
    Lease lease = Lease.builder().allow("cost.budget", "usd:5.50", "usd:1.25", "eur:3").build();
    Map<String, BigDecimal> budget = lease.budget();
    assertThat(budget)
        .containsEntry("usd", new BigDecimal("6.75"))
        .containsEntry("eur", new BigDecimal("3"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"usd", ":5", "usd:"})
  void budgetRejectsMalformedPatterns(String pattern) {
    Lease lease = Lease.builder().allow("cost.budget", pattern).build();
    assertThatThrownBy(lease::budget)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid cost.budget pattern");
  }

  @Test
  void containsComparesBudgetsNumerically() {
    Lease parent = Lease.builder().allow("cost.budget", "usd:10").build();
    assertThat(parent.contains(Lease.builder().allow("cost.budget", "usd:5").build())).isTrue();
    assertThat(parent.contains(Lease.builder().allow("cost.budget", "usd:10").build())).isTrue();
    assertThat(parent.contains(Lease.builder().allow("cost.budget", "usd:10.01").build()))
        .isFalse();
    assertThat(parent.contains(Lease.builder().allow("cost.budget", "eur:1").build())).isFalse();
  }

  @Test
  void containsRequiresParentNamespacePresence() {
    Lease parent = Lease.builder().allow("fs.read", "/a/**").build();
    assertThat(parent.contains(Lease.builder().allow("net.fetch", "example.com").build()))
        .isFalse();
    assertThat(parent.contains(Lease.empty())).isTrue();
  }

  @Test
  void containsAcceptsMixedNamespaceSubset() {
    Lease parent =
        Lease.builder()
            .allow("fs.read", "/data/**")
            .allow("cost.budget", "usd:100")
            .allow("tool.call", "*")
            .build();
    Lease child =
        Lease.builder()
            .allow("fs.read", "/data/in.txt")
            .allow("cost.budget", "usd:1")
            .allow("tool.call", "search")
            .build();
    assertThat(parent.contains(child)).isTrue();
  }

  @Test
  void patternsReturnsEmptyForUnknownNamespace() {
    assertThat(Lease.empty().patterns("fs.read")).isEmpty();
    assertThat(Lease.empty().budget()).isEmpty();
  }

  @Test
  void equalsHashCodeAndToString() {
    Lease a = Lease.builder().allow("fs.read", "/a").build();
    Lease b = Lease.builder().allow("fs.read", "/a").build();
    Lease c = Lease.builder().allow("fs.read", "/b").build();
    assertThat(a).isEqualTo(b).isNotEqualTo(c).isNotEqualTo("not-a-lease");
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
    assertThat(a.toString()).contains("fs.read");
  }

  @Test
  void builderMergesRepeatedAllowCalls() {
    Lease lease =
        Lease.builder().allow("fs.read", "/a").allow("fs.read", "/b").allow("fs.write").build();
    assertThat(lease.patterns("fs.read")).containsExactly("/a", "/b");
    assertThat(lease.patterns("fs.write")).isEmpty();
  }

  @Test
  void fromJsonNullYieldsEmptyLease() throws Exception {
    Method fromJson = Lease.class.getDeclaredMethod("fromJson", Map.class);
    fromJson.setAccessible(true);
    Lease lease = (Lease) fromJson.invoke(null, new Object[] {null});
    assertThat(lease).isEqualTo(Lease.empty());
  }

  @Test
  void jsonRoundTripPreservesCapabilities() throws Exception {
    ObjectMapper mapper = ArcpMapper.shared();
    Lease lease = mapper.readValue("{\"fs.read\":[\"/a/**\"]}", Lease.class);
    assertThat(lease.patterns("fs.read")).containsExactly("/a/**");
    assertThat(mapper.writeValueAsString(lease)).isEqualTo("{\"fs.read\":[\"/a/**\"]}");
  }

  @Test
  void capabilitiesViewIsImmutable() {
    Lease lease = Lease.builder().allow("fs.read", "/a").build();
    assertThatThrownBy(() -> lease.capabilities().put("x", List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
