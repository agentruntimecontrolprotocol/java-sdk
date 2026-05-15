package dev.arcp.runtime.agent;

/** User-supplied handler producing a job's events and final result. */
@FunctionalInterface
public interface Agent {
    JobOutcome run(JobInput input, JobContext ctx) throws Exception;
}
