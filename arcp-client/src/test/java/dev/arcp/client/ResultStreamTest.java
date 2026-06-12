package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.ids.ResultId;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ResultStreamTest {

  @Test
  void inOrderUtf8ChunksReassemble() throws Exception {
    ResultId id = ResultId.of("res_1");
    ResultStream stream = ResultStream.toMemory(id);
    stream.accept(new ResultChunkEvent(id, 0, "hello ", "utf8", true));
    stream.accept(new ResultChunkEvent(id, 1, "world", "utf8", false));
    assertThat(stream.isComplete()).isTrue();
    assertThat(new String(stream.bytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
  }

  @Test
  void outOfOrderArrivalIsHeldThenFlushed() throws Exception {
    ResultId id = ResultId.of("res_2");
    ResultStream stream = ResultStream.toMemory(id);
    stream.accept(new ResultChunkEvent(id, 1, "world", "utf8", false));
    assertThat(stream.isComplete()).isFalse();
    stream.accept(new ResultChunkEvent(id, 0, "hello ", "utf8", true));
    assertThat(stream.isComplete()).isTrue();
    assertThat(new String(stream.bytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
  }

  @Test
  void base64ChunksDecode() throws Exception {
    ResultId id = ResultId.of("res_3");
    byte[] raw = "abcdefghi".getBytes(StandardCharsets.UTF_8);
    String first = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(raw, 4));
    String second =
        Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(raw, 4, raw.length));
    ResultStream stream = ResultStream.toMemory(id);
    stream.accept(new ResultChunkEvent(id, 0, first, "base64", true));
    stream.accept(new ResultChunkEvent(id, 1, second, "base64", false));
    assertThat(stream.bytes()).isEqualTo(raw);
  }

  @Test
  void duplicateChunkRejected() throws Exception {
    ResultId id = ResultId.of("res_4");
    ResultStream stream = ResultStream.toMemory(id);
    stream.accept(new ResultChunkEvent(id, 0, "a", "utf8", true));
    // chunk_seq 0 has already been consumed (nextExpected is 1)
    assertThatThrownBy(() -> stream.accept(new ResultChunkEvent(id, 0, "a", "utf8", true)))
        .isInstanceOf(ResultStream.DuplicateChunkException.class);
  }

  @Test
  void duplicatePendingChunkRejectedAndIdenticalTolerated() throws Exception {
    ResultId id = ResultId.of("res_pending");
    ResultStream stream = ResultStream.toMemory(id);
    // chunk 1 arrives before its predecessor (still pending).
    stream.accept(new ResultChunkEvent(id, 1, "world", "utf8", false));
    // identical retransmission of a still-pending chunk is tolerated (#111).
    stream.accept(new ResultChunkEvent(id, 1, "world", "utf8", false));
    // a divergent copy of a still-pending chunk is rejected (#111).
    assertThatThrownBy(() -> stream.accept(new ResultChunkEvent(id, 1, "WORLD", "utf8", false)))
        .isInstanceOf(ResultStream.DuplicateChunkException.class);
  }

  @Test
  void rejectsWrongResultIdAndEncodingSwitches() throws Exception {
    ResultStream stream = ResultStream.toMemory(ResultId.of("res_expected"));
    assertThatThrownBy(
            () ->
                stream.accept(
                    new ResultChunkEvent(
                        ResultId.of("res_other"), 0, "a", ResultChunkEvent.UTF8, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wrong result_id");

    ResultStream encoding = ResultStream.toMemory(ResultId.of("res_encoding"));
    encoding.accept(
        new ResultChunkEvent(ResultId.of("res_encoding"), 0, "a", ResultChunkEvent.UTF8, true));
    assertThatThrownBy(
            () ->
                encoding.accept(
                    new ResultChunkEvent(
                        ResultId.of("res_encoding"),
                        1,
                        Base64.getEncoder().encodeToString("b".getBytes(StandardCharsets.UTF_8)),
                        ResultChunkEvent.BASE64,
                        false)))
        .isInstanceOf(ResultStream.EncodingMismatchException.class);
  }

  @Test
  void sinkModeTracksBytesAndRejectsInMemoryAccess() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    FilterOutputStream sink = new FilterOutputStream(bytes);
    ResultStream stream = ResultStream.toSink(ResultId.of("res_sink"), sink);
    stream.accept(new ResultChunkEvent(ResultId.of("res_sink"), 0, "abc", "utf8", false));
    assertThat(stream.bytesWritten()).isEqualTo(3);
    assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("abc");
    assertThatThrownBy(stream::bytes)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not in-memory");
    assertThatThrownBy(
            () ->
                stream.accept(
                    new ResultChunkEvent(ResultId.of("res_sink"), 1, "late", "utf8", false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already closed");
  }

  @Test
  void terminalChunkWithPendingBeyondItIsRejected() throws Exception {
    ResultStream stream = ResultStream.toMemory(ResultId.of("res_terminal"));
    stream.accept(new ResultChunkEvent(ResultId.of("res_terminal"), 1, "late", "utf8", true));
    assertThatThrownBy(
            () ->
                stream.accept(
                    new ResultChunkEvent(ResultId.of("res_terminal"), 0, "done", "utf8", false)))
        .isInstanceOf(ResultStream.OutOfOrderChunkException.class)
        .hasMessageContaining("chunks beyond terminal");
  }
}
