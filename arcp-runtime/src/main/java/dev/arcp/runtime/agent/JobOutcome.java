package dev.arcp.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.ids.ResultId;
import org.jspecify.annotations.Nullable;

/**
 * Terminal result of {@link Agent#run}: either a {@link Success} mapped to {@code job.result} or a
 * {@link Failure} mapped to {@code job.error} (§7.3).
 */
public sealed interface JobOutcome permits JobOutcome.Success, JobOutcome.Failure {

  /**
   * Successful completion. Exactly one of {@code inline} or {@code resultId} should be set: small
   * results are returned inline, large ones are streamed as {@code result_chunk} events and
   * referenced by id (§8.4).
   *
   * @param inline the inline result document, or {@code null} when the result was streamed
   * @param resultId the id of a streamed result, or {@code null} for inline results
   * @param resultSize the total byte size of a streamed result, or {@code null} for inline results
   * @param summary an optional human-readable summary of the result
   */
  record Success(
      @Nullable JsonNode inline,
      @Nullable ResultId resultId,
      @Nullable Long resultSize,
      @Nullable String summary)
      implements JobOutcome {

    /**
     * Creates a success whose result is carried inline in {@code job.result}.
     *
     * @param result the result document
     * @return an inline success outcome
     */
    public static Success inline(JsonNode result) {
      return new Success(result, null, null, null);
    }

    /**
     * Creates a success whose result was streamed as {@code result_chunk} events (§8.4).
     *
     * @param id the result id the chunks were emitted under
     * @param size the total size of the streamed result in bytes
     * @param summary an optional human-readable summary, or {@code null}
     * @return a streamed success outcome
     */
    public static Success streamed(ResultId id, long size, @Nullable String summary) {
      return new Success(null, id, size, summary);
    }
  }

  /**
   * Failed completion, surfaced to the client as {@code job.error} (§12).
   *
   * @param code the §12 error code describing the failure
   * @param message a human-readable description of the failure
   */
  record Failure(ErrorCode code, String message) implements JobOutcome {}
}
