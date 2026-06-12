package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * §6.6 {@code session.list_jobs} payload: requests a read-only, principal-scoped job inventory.
 * Listing does not subscribe to events; use {@code job.subscribe} (§7.6) for that.
 *
 * @param filter optional filter on status, agent, and creation time, or {@code null} for all jobs
 * @param limit maximum number of jobs to return, or {@code null} for the runtime default
 * @param cursor opaque pagination cursor from a prior {@code session.jobs}, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionListJobs(
    @Nullable JobFilter filter, @Nullable Integer limit, @Nullable String cursor)
    implements Message {
  /** Canonical constructor. */
  @JsonCreator
  public SessionListJobs(
      @JsonProperty("filter") @Nullable JobFilter filter,
      @JsonProperty("limit") @Nullable Integer limit,
      @JsonProperty("cursor") @Nullable String cursor) {
    this.filter = filter;
    this.limit = limit;
    this.cursor = cursor;
  }

  @Override
  public Type kind() {
    return Type.SESSION_LIST_JOBS;
  }
}
