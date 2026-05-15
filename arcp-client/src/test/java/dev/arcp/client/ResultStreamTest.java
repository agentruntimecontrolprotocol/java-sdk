package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.ids.ResultId;
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
        String second = Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOfRange(raw, 4, raw.length));
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
}
