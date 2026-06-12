package dev.arcp.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of one accepted {@code job.submit}, handed to {@link Agent#run}.
 *
 * @param payload the submitted {@code input} document, opaque to the runtime
 * @param jobId the id assigned at acceptance (§7.1)
 * @param sessionId the session the job was submitted on
 * @param traceId the propagated trace id, or {@code null} if the client sent none (§11)
 * @param lease the granted lease the job runs under (§9)
 * @param credentials provisioned credentials issued for this job, empty when none (§9.8)
 */
public record JobInput(
    JsonNode payload,
    JobId jobId,
    SessionId sessionId,
    @Nullable TraceId traceId,
    Lease lease,
    List<Credential> credentials) {
  /** Defensively copies {@code credentials} into an immutable list. */
  public JobInput {
    credentials = List.copyOf(credentials);
  }
}
