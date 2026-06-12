package dev.arcp.runtime.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.wire.ArcpMapper;
import org.junit.jupiter.api.Test;

class IdempotencyFingerprintTest {

  @Test
  void keyOrderDoesNotAffectFingerprint() {
    ObjectNode a = JsonNodeFactory.instance.objectNode();
    a.put("b", 1);
    a.put("a", 2);
    ObjectNode b = JsonNodeFactory.instance.objectNode();
    b.put("a", 2);
    b.put("b", 1);
    JobSubmit s1 = new JobSubmit(AgentRef.parse("echo@1.0.0"), a, null, null, "k", null);
    JobSubmit s2 = new JobSubmit(AgentRef.parse("echo@1.0.0"), b, null, null, "k", null);
    assertThat(IdempotencyFingerprint.of(ArcpMapper.shared(), s1))
        .isEqualTo(IdempotencyFingerprint.of(ArcpMapper.shared(), s2));
  }

  @Test
  void differentInputsDifferentFingerprint() {
    JobSubmit s1 =
        new JobSubmit(
            AgentRef.parse("echo@1.0.0"),
            JsonNodeFactory.instance.objectNode().put("x", 1),
            null,
            null,
            "k",
            null);
    JobSubmit s2 =
        new JobSubmit(
            AgentRef.parse("echo@1.0.0"),
            JsonNodeFactory.instance.objectNode().put("x", 2),
            null,
            null,
            "k",
            null);
    assertThat(IdempotencyFingerprint.of(ArcpMapper.shared(), s1))
        .isNotEqualTo(IdempotencyFingerprint.of(ArcpMapper.shared(), s2));
  }
}
