package dev.arcp.runtime.session;

import dev.arcp.core.auth.Principal;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.core.messages.JobSummary;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * §6.6 {@code session.list_jobs} filtering and paging. Filters a job registry by principal and the
 * requested {@link JobFilter}, orders by a stable key (createdAt, then jobId), and slices the
 * requested page — mapping only the page to {@link JobSummary} rather than every matching job
 * (#83).
 */
public final class JobListing {

  private JobListing() {}

  /** A page of job summaries plus the opaque cursor for the next page (or {@code null}). */
  public record Page(List<JobSummary> jobs, @Nullable String nextCursor) {}

  /**
   * @throws IllegalArgumentException if {@code cursor} is non-blank but not a valid cursor
   */
  public static Page page(
      Collection<JobRecord> jobs,
      Principal principal,
      JobFilter filter,
      @Nullable Integer limit,
      @Nullable String cursor) {
    int startIndex = decodeCursor(cursor);

    List<JobRecord> matching =
        jobs.stream()
            .filter(rec -> rec.principal().equals(principal))
            .filter(rec -> filter.status() == null || filter.status().contains(rec.status().wire()))
            .filter(
                rec ->
                    filter.agent() == null
                        || filter.agent().equals(rec.resolvedAgent())
                        || filter.agent().equals(rec.resolvedAgent().split("@", 2)[0]))
            .filter(
                rec ->
                    filter.createdAfter() == null || rec.createdAt().isAfter(filter.createdAfter()))
            .sorted(
                Comparator.comparing((JobRecord rec) -> rec.createdAt())
                    .thenComparing(rec -> rec.jobId().value()))
            .toList();

    int effectiveLimit = limit != null && limit > 0 ? limit : matching.size();
    int endIndex = Math.min(matching.size(), startIndex + effectiveLimit);
    List<JobSummary> page =
        startIndex >= matching.size()
            ? List.of()
            : matching.subList(startIndex, endIndex).stream().map(JobListing::toSummary).toList();
    String nextCursor = endIndex < matching.size() ? encodeCursor(endIndex) : null;
    return new Page(page, nextCursor);
  }

  private static int decodeCursor(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(cursor);
      return Math.max(0, Integer.parseInt(new String(decoded, StandardCharsets.UTF_8)));
    } catch (IllegalArgumentException e) {
      // Covers malformed Base64 and non-numeric content (NumberFormatException): surface a stable
      // message instead of the parser's, since the caller echoes it in INVALID_REQUEST.
      throw new IllegalArgumentException("invalid cursor", e);
    }
  }

  private static String encodeCursor(int index) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Integer.toString(index).getBytes(StandardCharsets.UTF_8));
  }

  private static JobSummary toSummary(JobRecord rec) {
    return new JobSummary(
        rec.jobId(),
        rec.resolvedAgent(),
        rec.status().wire(),
        rec.lease(),
        null,
        rec.createdAt(),
        rec.traceId(),
        rec.lastEventSeq());
  }
}
