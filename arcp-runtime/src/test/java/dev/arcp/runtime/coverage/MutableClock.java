package dev.arcp.runtime.coverage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic test clock; advances only when told to. */
final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;
  private volatile boolean throwOnRead;

  MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  void advance(Duration d) {
    now.updateAndGet(i -> i.plus(d));
  }

  void set(Instant instant) {
    now.set(instant);
  }

  void throwOnRead(boolean value) {
    this.throwOnRead = value;
  }

  @Override
  public Instant instant() {
    if (throwOnRead) {
      throw new IllegalStateException("clock read failure (test)");
    }
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
