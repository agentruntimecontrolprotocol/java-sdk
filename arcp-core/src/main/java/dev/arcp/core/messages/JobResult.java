package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.ids.ResultId;
import org.jspecify.annotations.Nullable;

/**
 * §7 / §8.4 terminal job.result. When {@code resultId} is present the result is the concatenation
 * of streamed {@code result_chunk} bodies; otherwise the inline {@code result} payload carries the
 * result directly.
 *
 * @param finalStatus terminal status ({@code final_status}), {@link #SUCCESS} on success
 * @param resultId id of the §8.4 streamed result ({@code result_id}), or {@code null} when the
 *     result is inline
 * @param resultSize total byte size of the assembled result ({@code result_size}), or {@code null}
 * @param result inline result payload, or {@code null} when streamed
 * @param summary human-readable result summary, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResult(
    @JsonProperty("final_status") String finalStatus,
    @JsonProperty("result_id") @Nullable ResultId resultId,
    @JsonProperty("result_size") @Nullable Long resultSize,
    @Nullable JsonNode result,
    @Nullable String summary)
    implements Message {

  /** {@code final_status} for jobs that completed successfully (§7.3). */
  public static final String SUCCESS = "success";

  /** Canonical constructor. */
  @JsonCreator
  public JobResult(
      @JsonProperty("final_status") String finalStatus,
      @JsonProperty("result_id") @Nullable ResultId resultId,
      @JsonProperty("result_size") @Nullable Long resultSize,
      @JsonProperty("result") @Nullable JsonNode result,
      @JsonProperty("summary") @Nullable String summary) {
    this.finalStatus = finalStatus;
    this.resultId = resultId;
    this.resultSize = resultSize;
    this.result = result;
    this.summary = summary;
  }

  @Override
  public Type kind() {
    return Type.JOB_RESULT;
  }
}
