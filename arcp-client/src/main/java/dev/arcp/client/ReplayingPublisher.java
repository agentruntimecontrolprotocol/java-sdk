package dev.arcp.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Multicast {@link Flow.Publisher} that buffers every emission and replays the buffer to each newly
 * attached subscriber before forwarding live deliveries. Used by {@link JobHandle#events()} so
 * callers subscribing after a job has already emitted still see the full event history.
 *
 * <p>Each {@code subscribe} delivers {@code onSubscribe} as its first signal (Reactive Streams
 * §1.9): the replay snapshot and any live items that race in during replay are queued until the
 * downstream subscriber has acknowledged the subscription.
 */
final class ReplayingPublisher<T> implements Flow.Publisher<T>, AutoCloseable {

  private final List<T> buffer = new CopyOnWriteArrayList<>();
  private final SubmissionPublisher<T> live;
  private final ExecutorService liveExecutor;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile boolean closed;

  ReplayingPublisher() {
    this.liveExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.live = new SubmissionPublisher<>(liveExecutor, 1024);
  }

  // Lock spans live.submit so concurrent producers preserve buffer/live order;
  // back-pressure blocking on a full submission queue therefore stalls peers.
  void submit(T item) {
    lock.lock();
    try {
      buffer.add(item);
      live.submit(item);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      live.close();
    } finally {
      lock.unlock();
    }
    liveExecutor.shutdown();
  }

  @Override
  public void subscribe(Flow.Subscriber<? super T> downstream) {
    final List<T> snapshot;
    final boolean wasClosed;
    AtomicBoolean cancelled = new AtomicBoolean(false);
    AtomicBoolean replayDone = new AtomicBoolean(false);
    Deque<T> pending = new ArrayDeque<>();
    Object pendingLock = new Object();

    lock.lock();
    try {
      snapshot = new ArrayList<>(buffer);
      wasClosed = closed;
      if (!wasClosed) {
        live.subscribe(
            new Flow.Subscriber<T>() {
              @Override
              public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(T item) {
                if (cancelled.get()) {
                  return;
                }
                if (!replayDone.get()) {
                  synchronized (pendingLock) {
                    if (!replayDone.get()) {
                      pending.addLast(item);
                      return;
                    }
                  }
                }
                downstream.onNext(item);
              }

              @Override
              public void onError(Throwable throwable) {
                if (!cancelled.get()) {
                  downstream.onError(throwable);
                }
              }

              @Override
              public void onComplete() {
                if (!cancelled.get()) {
                  downstream.onComplete();
                }
              }
            });
      }
    } finally {
      lock.unlock();
    }

    downstream.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            // Replay is delivered eagerly below; live items honor the
            // forwarder's request(MAX_VALUE).
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
    for (T item : snapshot) {
      if (cancelled.get()) {
        return;
      }
      downstream.onNext(item);
    }
    // Drain any items the live forwarder buffered during replay, then flip the
    // flag so subsequent live deliveries flow straight through.
    synchronized (pendingLock) {
      while (!pending.isEmpty()) {
        if (cancelled.get()) {
          return;
        }
        downstream.onNext(pending.pollFirst());
      }
      replayDone.set(true);
    }
    if (wasClosed) {
      downstream.onComplete();
    }
  }
}
