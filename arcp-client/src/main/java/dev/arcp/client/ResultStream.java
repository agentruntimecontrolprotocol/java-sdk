package dev.arcp.client;

import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.ids.ResultId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * §8.4 result_chunk reassembler. Chunks may arrive out of order (the spec
 * mandates emission order, but processing order at the subscriber is not
 * guaranteed); we hold pending chunks in a {@link TreeMap} and flush them when
 * their predecessor arrives.
 */
public final class ResultStream {

    public static final class OutOfOrderChunkException extends RuntimeException {
        public OutOfOrderChunkException(String message) {
            super(message);
        }
    }

    public static final class DuplicateChunkException extends RuntimeException {
        public DuplicateChunkException(long chunkSeq) {
            super("duplicate chunk_seq " + chunkSeq);
        }
    }

    public static final class EncodingMismatchException extends RuntimeException {
        public EncodingMismatchException(String first, String later) {
            super("encoding switched mid-stream: " + first + " -> " + later);
        }
    }

    private final @Nullable ResultId resultId;
    private final OutputStream sink;
    private final TreeMap<Long, ResultChunkEvent> pending = new TreeMap<>();
    private long nextExpected;
    private long bytesWritten;
    private boolean closed;
    private @Nullable String encoding;

    private ResultStream(@Nullable ResultId resultId, OutputStream sink) {
        this.resultId = resultId;
        this.sink = sink;
    }

    /** In-memory sink; {@link #bytes()} returns the assembled output. */
    public static ResultStream toMemory(@Nullable ResultId resultId) {
        return new ResultStream(resultId, new ByteArrayOutputStream());
    }

    /** Stream chunks directly to an arbitrary {@link OutputStream}. */
    public static ResultStream toSink(@Nullable ResultId resultId, OutputStream sink) {
        return new ResultStream(resultId, sink);
    }

    public synchronized void accept(ResultChunkEvent chunk) throws IOException {
        if (closed) {
            throw new IllegalStateException("ResultStream already closed");
        }
        if (resultId != null && !resultId.equals(chunk.resultId())) {
            throw new IllegalArgumentException(
                    "chunk for wrong result_id: " + chunk.resultId() + " != " + resultId);
        }
        if (encoding == null) {
            encoding = chunk.encoding();
        } else if (!encoding.equals(chunk.encoding())) {
            throw new EncodingMismatchException(encoding, chunk.encoding());
        }
        if (chunk.chunkSeq() < nextExpected) {
            throw new DuplicateChunkException(chunk.chunkSeq());
        }
        pending.put(chunk.chunkSeq(), chunk);
        while (pending.containsKey(nextExpected)) {
            ResultChunkEvent ready = pending.remove(nextExpected);
            byte[] decoded = decode(ready);
            sink.write(decoded);
            bytesWritten += decoded.length;
            nextExpected++;
            if (!ready.more()) {
                if (!pending.isEmpty()) {
                    throw new OutOfOrderChunkException(
                            "chunks beyond terminal: " + pending.keySet());
                }
                closed = true;
                sink.flush();
            }
        }
    }

    private static byte[] decode(ResultChunkEvent chunk) {
        return switch (chunk.encoding()) {
            case ResultChunkEvent.UTF8 -> chunk.data().getBytes(StandardCharsets.UTF_8);
            case ResultChunkEvent.BASE64 -> Base64.getDecoder().decode(chunk.data());
            default -> throw new IllegalStateException("unsupported encoding: " + chunk.encoding());
        };
    }

    public synchronized boolean isComplete() {
        return closed;
    }

    public synchronized long bytesWritten() {
        return bytesWritten;
    }

    /** Assembled bytes from an in-memory stream; throws if a non-memory sink is in use. */
    public synchronized byte[] bytes() {
        if (!(sink instanceof ByteArrayOutputStream baos)) {
            throw new IllegalStateException("ResultStream sink is not in-memory");
        }
        return baos.toByteArray();
    }
}
