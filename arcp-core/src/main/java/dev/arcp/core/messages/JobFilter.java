package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Filter block of a §6.6 {@code session.list_jobs} request. All fields are optional; {@code null}
 * leaves that dimension unconstrained.
 *
 * @param status job statuses to include (e.g. {@code running}, {@code pending}), or {@code null}
 * @param agent agent reference to match, or {@code null}
 * @param createdAfter lower bound on creation time ({@code created_after}), or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobFilter(
    @Nullable List<String> status,
    @Nullable String agent,
    @JsonProperty("created_after") @Nullable Instant createdAfter) {
  /** Canonical constructor; {@code status} is defensively copied. */
  @JsonCreator
  public JobFilter(
      @JsonProperty("status") @Nullable List<String> status,
      @JsonProperty("agent") @Nullable String agent,
      @JsonProperty("created_after") @Nullable Instant createdAfter) {
    this.status = status == null ? null : List.copyOf(status);
    this.agent = agent;
    this.createdAfter = createdAfter;
  }

  /**
   * Returns the filter matching every job.
   *
   * @return an unconstrained filter
   */
  public static JobFilter all() {
    return new JobFilter(null, null, null);
  }
}
