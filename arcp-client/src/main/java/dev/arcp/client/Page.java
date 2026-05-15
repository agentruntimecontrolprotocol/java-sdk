package dev.arcp.client;

import dev.arcp.core.messages.JobSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Page<T>(List<T> items, @Nullable String nextCursor) {
    public static Page<JobSummary> empty() {
        return new Page<>(List.of(), null);
    }

    public boolean hasNext() {
        return nextCursor != null;
    }
}
