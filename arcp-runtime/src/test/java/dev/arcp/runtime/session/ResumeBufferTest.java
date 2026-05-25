package dev.arcp.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.wire.Envelope;
import org.junit.jupiter.api.Test;

class ResumeBufferTest {

  @Test
  void storesOnlySequencedEnvelopesWithinCapacity() {
    ResumeBuffer buffer = new ResumeBuffer(2);
    assertThat(buffer.earliestSeq()).isEqualTo(-1);
    buffer.record(envelope("m_ignored", null));
    assertThat(buffer.since(0)).isEmpty();

    buffer.record(envelope("m_1", 1L));
    buffer.record(envelope("m_2", 2L));
    buffer.record(envelope("m_3", 3L));

    assertThat(buffer.earliestSeq()).isEqualTo(2);
    assertThat(buffer.since(1))
        .extracting(Envelope::id)
        .containsExactly(MessageId.of("m_2"), MessageId.of("m_3"));
    assertThat(buffer.since(2)).extracting(Envelope::id).containsExactly(MessageId.of("m_3"));
  }

  @Test
  void rejectsInvalidCapacityAndNullEnvelope() {
    assertThatThrownBy(() -> new ResumeBuffer(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacity");
    ResumeBuffer buffer = new ResumeBuffer(1);
    assertThatThrownBy(() -> buffer.record(null)).isInstanceOf(NullPointerException.class);
  }

  private static Envelope envelope(String id, Long eventSeq) {
    return new Envelope(
        Envelope.VERSION,
        MessageId.of(id),
        "job.event",
        null,
        null,
        null,
        eventSeq,
        JsonNodeFactory.instance.objectNode());
  }
}
