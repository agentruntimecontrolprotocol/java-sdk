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
 * §8.4 result_chunk reassembler. Chunks may arrive out of order (the spec mandates emission order,
 * but processing order at the subscriber is not guaranteed); we hold pending chunks in a {@link
 * TreeMap} and flush them when their predecessor arrives.
 */
public final class ResultStream {

  /** Raised when chunks remain pending after the terminal chunk ({@code more=false}, §8.4). */
  public static final class OutOfOrderChunkException extends RuntimeException {
    /**
     * Creates the exception with a description of the ordering violation.
     *
     * @param message detail of the violation, including the orphaned {@code chunk_seq} values
     */
    public OutOfOrderChunkException(String message) {
      super(message);
    }
  }

  /** Raised when a {@code chunk_seq} that was already flushed or buffered arrives again (§8.4). */
  public static final class DuplicateChunkException extends RuntimeException {
    /**
     * Creates the exception for the duplicated sequence number.
     *
     * @param chunkSeq the {@code chunk_seq} that was delivered more than once
     */
    public DuplicateChunkException(long chunkSeq) {
      super("duplicate chunk_seq " + chunkSeq);
    }
  }

  /** Raised when a chunk's {@code encoding} differs from the first chunk's encoding (§8.4). */
  public static final class EncodingMismatchException extends RuntimeException {
    /**
     * Creates the exception describing the encoding switch.
     *
     * @param first the encoding declared by the first chunk
     * @param later the conflicting encoding declared by a later chunk
     */
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

  /**
   * In-memory sink; {@link #bytes()} returns the assembled output.
   *
   * @param resultId expected {@code result_id}, or {@code null} to accept chunks for any result
   * @return a stream that assembles chunks into an in-memory buffer
   */
  public static ResultStream toMemory(@Nullable ResultId resultId) {
    return new ResultStream(resultId, new ByteArrayOutputStream());
  }

  /**
   * Stream chunks directly to an arbitrary {@link OutputStream}.
   *
   * @param resultId expected {@code result_id}, or {@code null} to accept chunks for any result
   * @param sink destination for decoded chunk bytes; flushed when the terminal chunk lands
   * @return a stream that writes assembled output to {@code sink}
   */
  public static ResultStream toSink(@Nullable ResultId resultId, OutputStream sink) {
    return new ResultStream(resultId, sink);
  }

  /**
   * Accepts one {@code result_chunk} event (§8.4), buffering it until its predecessors arrive and
   * then flushing in {@code chunk_seq} order. The terminal chunk ({@code more=false}) completes the
   * stream and flushes the sink.
   *
   * @param chunk the chunk to ingest; its {@code result_id} must match this stream's, if set
   * @throws IOException if writing the decoded bytes to the sink fails
   */
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
    ResultChunkEvent existing = pending.get(chunk.chunkSeq());
    if (existing != null) {
      // §8.4: a duplicate of a still-pending chunk is rejected like any other duplicate. A
      // byte-identical retransmission is tolerated (idempotent); a divergent copy is an error.
      if (existing.equals(chunk)) {
        return;
      }
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
          throw new OutOfOrderChunkException("chunks beyond terminal: " + pending.keySet());
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

  /**
   * Returns whether the terminal chunk ({@code more=false}) has been flushed.
   *
   * @return {@code true} once the stream is complete and no further chunks are accepted
   */
  public synchronized boolean isComplete() {
    return closed;
  }

  /**
   * Returns the number of decoded bytes flushed to the sink so far.
   *
   * @return the running count of bytes written, excluding still-pending out-of-order chunks
   */
  public synchronized long bytesWritten() {
    return bytesWritten;
  }

  /**
   * Assembled bytes from an in-memory stream; throws if a non-memory sink is in use.
   *
   * @return the bytes flushed so far to the in-memory buffer created by {@link #toMemory}
   */
  public synchronized byte[] bytes() {
    if (!(sink instanceof ByteArrayOutputStream baos)) {
      throw new IllegalStateException("ResultStream sink is not in-memory");
    }
    return baos.toByteArray();
  }
}
