package dev.arcp.client;

import dev.arcp.core.messages.JobSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a cursor-paginated listing, as returned by {@link ArcpClient#listJobs} from a {@code
 * session.jobs} response (§6.6).
 *
 * @param <T> the element type, e.g. {@link JobSummary} for job listings
 * @param items the elements of this page; defensively copied, never {@code null}
 * @param nextCursor the {@code next_cursor} to pass to the next listing call, or {@code null} if
 *     this is the final page
 */
public record Page<T>(List<T> items, @Nullable String nextCursor) {
  /** Canonical constructor; copies {@code items} into an immutable view ({@code null} = empty). */
  public Page {
    items = items == null ? List.of() : List.copyOf(items);
  }

  /**
   * Returns an empty job-listing page with no continuation cursor.
   *
   * @return a page with no items and a {@code null} cursor
   */
  public static Page<JobSummary> empty() {
    return new Page<>(List.of(), null);
  }

  /**
   * Returns whether another page can be fetched.
   *
   * @return {@code true} if {@link #nextCursor()} is non-null and more results remain
   */
  public boolean hasNext() {
    return nextCursor != null;
  }
}
