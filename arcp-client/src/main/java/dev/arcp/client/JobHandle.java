package dev.arcp.client;

import dev.arcp.core.credentials.Credential;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobResult;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Client-side handle to one submitted job. */
public interface JobHandle {

  /**
   * Returns the runtime-assigned job identifier from {@code job.accepted}.
   *
   * @return the job id
   */
  JobId jobId();

  /**
   * Returns the fully resolved agent reference the runtime selected for this job, e.g. {@code
   * code-refactor@2.0.0} (§7.5).
   *
   * @return the resolved {@code agent} value echoed on {@code job.accepted}
   */
  String resolvedAgent();

  /**
   * Returns the {@code job.accepted} payload for this job, carrying the effective lease,
   * constraints, and initial budget counters (§7.1).
   *
   * @return the acceptance message as received from the runtime
   */
  JobAccepted accepted();

  /**
   * Returns the provisioned credentials attached to {@code job.accepted}, if any (§9.8).
   *
   * @return the credentials list, or {@link Optional#empty()} when none were provisioned
   */
  default Optional<List<Credential>> credentials() {
    return Optional.ofNullable(accepted().credentials());
  }

  /**
   * Hot publisher of {@link EventBody} for this job's {@code job.event} stream.
   *
   * @return a publisher that replays buffered events to late subscribers and then streams live
   */
  Flow.Publisher<EventBody> events();

  /**
   * Completes with {@link JobResult} on success or fails with {@link ArcpException}.
   *
   * @return a future tracking the job's terminal outcome
   */
  CompletableFuture<JobResult> result();

  /**
   * Requests cancellation by sending {@code job.cancel} (§7.4). The terminal {@code job.error} with
   * code {@code CANCELLED} that follows completes {@link #result()} exceptionally.
   */
  void cancel();
}
