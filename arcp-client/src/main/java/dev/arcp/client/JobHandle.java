package dev.arcp.client;

import dev.arcp.core.credentials.Credential;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobResult;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Client-side handle to one submitted job. */
public interface JobHandle {

    JobId jobId();

    String resolvedAgent();

    JobAccepted accepted();

    default Optional<List<Credential>> credentials() {
        return Optional.ofNullable(accepted().credentials());
    }

    /** Hot publisher of {@link EventBody} for this job's {@code job.event} stream. */
    Flow.Publisher<EventBody> events();

    /** Completes with {@link JobResult} on success or fails with {@link ArcpException}. */
    CompletableFuture<JobResult> result();

    void cancel();
}
