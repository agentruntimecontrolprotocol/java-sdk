package dev.arcp.client;

/**
 * Options for {@link ArcpClient#subscribe(dev.arcp.core.ids.JobId, SubscribeOptions)} controlling
 * whether a {@code job.subscribe} request replays buffered event history (§7.6).
 *
 * @param history whether to request replay of buffered events; when {@code false} the subscriber
 *     only sees events emitted after the subscription is acknowledged
 * @param fromEventSeq when {@code history} is {@code true}, the runtime replays buffered events
 *     with {@code seq > fromEventSeq} before resuming live streaming; ignored otherwise
 */
public record SubscribeOptions(boolean history, long fromEventSeq) {
  /**
   * Returns options for a live-only subscription: no history replay, only events emitted after the
   * subscription is acknowledged (§7.6).
   *
   * @return options with {@code history} disabled
   */
  public static SubscribeOptions live() {
    return new SubscribeOptions(false, 0);
  }

  /**
   * Returns options requesting replay of buffered history before live streaming resumes (§7.6).
   *
   * @param fromEventSeq sequence number to replay from; the runtime replays buffered events with
   *     {@code seq > fromEventSeq}, bounded by the same buffer window that governs resume
   * @return options with {@code history} enabled starting at {@code fromEventSeq}
   */
  public static SubscribeOptions withHistory(long fromEventSeq) {
    return new SubscribeOptions(true, fromEventSeq);
  }
}
