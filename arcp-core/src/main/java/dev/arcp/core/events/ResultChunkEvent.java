package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.arcp.core.ids.ResultId;

/**
 * §8.4 result_chunk. {@code encoding} is one of {@code utf8} or {@code base64}. Chunks for one
 * {@code result_id} MUST be emitted in {@code chunk_seq} order.
 *
 * @param resultId stable id of the assembled result ({@code result_id})
 * @param chunkSeq 0-based monotonic chunk index per result ({@code chunk_seq})
 * @param data chunk payload, text or Base64 bytes per {@code encoding}
 * @param encoding {@link #UTF8} or {@link #BASE64}
 * @param more {@code true} if additional chunks follow, {@code false} on the final chunk
 */
public record ResultChunkEvent(
    @JsonProperty("result_id") ResultId resultId,
    @JsonProperty("chunk_seq") long chunkSeq,
    String data,
    String encoding,
    boolean more)
    implements EventBody {

  /** Wire {@code encoding} for text chunks: {@code data} is the raw text fragment. */
  public static final String UTF8 = "utf8";

  /** Wire {@code encoding} for binary chunks: {@code data} is Base64-encoded bytes. */
  public static final String BASE64 = "base64";

  /**
   * Canonical constructor enforcing the §8.4 field constraints.
   *
   * @throws IllegalArgumentException if {@code chunkSeq} is negative or {@code encoding} is neither
   *     {@code utf8} nor {@code base64}
   */
  @JsonCreator
  public ResultChunkEvent(
      @JsonProperty("result_id") ResultId resultId,
      @JsonProperty("chunk_seq") long chunkSeq,
      @JsonProperty("data") String data,
      @JsonProperty("encoding") String encoding,
      @JsonProperty("more") boolean more) {
    if (chunkSeq < 0) {
      throw new IllegalArgumentException("chunk_seq must be non-negative: " + chunkSeq);
    }
    if (!UTF8.equals(encoding) && !BASE64.equals(encoding)) {
      throw new IllegalArgumentException("encoding must be utf8 or base64: " + encoding);
    }
    this.resultId = resultId;
    this.chunkSeq = chunkSeq;
    this.data = data;
    this.encoding = encoding;
    this.more = more;
  }

  @Override
  public Kind kind() {
    return Kind.RESULT_CHUNK;
  }
}
