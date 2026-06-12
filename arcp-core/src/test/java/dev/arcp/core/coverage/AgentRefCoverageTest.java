package dev.arcp.core.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.agents.AgentRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Branch coverage for §7.5 agent reference parsing. */
class AgentRefCoverageTest {

  @Test
  void parseWithoutVersion() {
    AgentRef ref = AgentRef.parse("echo");
    assertThat(ref.name()).isEqualTo("echo");
    assertThat(ref.version()).isNull();
    assertThat(ref.versionOpt()).isEmpty();
    assertThat(ref.wire()).isEqualTo("echo");
    assertThat(ref).hasToString("echo");
  }

  @Test
  void parseWithVersion() {
    AgentRef ref = AgentRef.parse("echo@1.2.3-beta+build_7");
    assertThat(ref.name()).isEqualTo("echo");
    assertThat(ref.version()).isEqualTo("1.2.3-beta+build_7");
    assertThat(ref.versionOpt()).contains("1.2.3-beta+build_7");
    assertThat(ref.wire()).isEqualTo("echo@1.2.3-beta+build_7");
    assertThat(ref).hasToString("echo@1.2.3-beta+build_7");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Echo", "_agent", "", "agent name", "-lead"})
  void rejectsInvalidNames(String raw) {
    assertThatThrownBy(() -> AgentRef.parse(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid agent name");
  }

  @ParameterizedTest
  @ValueSource(strings = {"echo@", "echo@1 0", "echo@v!"})
  void rejectsInvalidVersions(String raw) {
    assertThatThrownBy(() -> AgentRef.parse(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid agent version");
  }

  @Test
  void rejectsNullInputs() {
    assertThatThrownBy(() -> AgentRef.parse(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AgentRef(null, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructorAcceptsExplicitNullVersion() {
    AgentRef ref = new AgentRef("planner.v2", null);
    assertThat(ref.wire()).isEqualTo("planner.v2");
  }
}
