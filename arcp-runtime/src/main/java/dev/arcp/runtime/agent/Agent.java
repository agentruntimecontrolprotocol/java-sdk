package dev.arcp.runtime.agent;

/** User-supplied handler producing a job's events and final result. */
@FunctionalInterface
public interface Agent {

  /**
   * Runs one job to completion. Invoked on the runtime's worker pool after {@code job.accepted};
   * implementations should emit progress via {@link JobContext#emit} and poll {@link
   * JobContext#cancelled()} at convenient checkpoints (§7.4).
   *
   * @param input the submitted payload plus job identity, lease, and provisioned credentials
   * @param ctx per-job runtime services: event emission, cancellation, and lease checks
   * @return the job's terminal outcome (success or failure)
   * @throws Exception if the job fails; the runtime maps the failure to a {@code job.error} with an
   *     appropriate §12 error code
   */
  JobOutcome run(JobInput input, JobContext ctx) throws Exception;
}
