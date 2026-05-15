package dev.arcp.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.ids.ResultId;
import org.jspecify.annotations.Nullable;

public sealed interface JobOutcome permits JobOutcome.Success, JobOutcome.Failure {

    record Success(
            @Nullable JsonNode inline,
            @Nullable ResultId resultId,
            @Nullable Long resultSize,
            @Nullable String summary)
            implements JobOutcome {

        public static Success inline(JsonNode result) {
            return new Success(result, null, null, null);
        }

        public static Success streamed(ResultId id, long size, @Nullable String summary) {
            return new Success(null, id, size, summary);
        }
    }

    record Failure(ErrorCode code, String message) implements JobOutcome {}
}
