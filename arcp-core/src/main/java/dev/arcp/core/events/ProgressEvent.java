package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §8.2.1 progress event. {@code current} MUST be ≥ 0. If {@code total} is
 * present, {@code current} SHOULD be ≤ {@code total}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProgressEvent(
        long current,
        @Nullable Long total,
        @Nullable String units,
        @Nullable String message)
        implements EventBody {

    @JsonCreator
    public ProgressEvent(
            @JsonProperty("current") long current,
            @JsonProperty("total") @Nullable Long total,
            @JsonProperty("units") @Nullable String units,
            @JsonProperty("message") @Nullable String message) {
        if (current < 0) {
            throw new IllegalArgumentException("current must be non-negative: " + current);
        }
        if (total != null && total < 0) {
            throw new IllegalArgumentException("total must be non-negative: " + total);
        }
        this.current = current;
        this.total = total;
        this.units = units;
        this.message = message;
    }

    @Override
    public Kind kind() {
        return Kind.PROGRESS;
    }
}
