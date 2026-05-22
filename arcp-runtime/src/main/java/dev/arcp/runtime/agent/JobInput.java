package dev.arcp.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record JobInput(
        JsonNode payload,
        JobId jobId,
        SessionId sessionId,
        @Nullable TraceId traceId,
        Lease lease,
        List<Credential> credentials) {
    public JobInput {
        credentials = List.copyOf(credentials);
    }
}
