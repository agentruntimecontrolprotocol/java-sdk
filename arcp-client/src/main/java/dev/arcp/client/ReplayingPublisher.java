package dev.arcp.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multicast {@link Flow.Publisher} that buffers every emission and replays the
 * buffer to each newly attached subscriber before forwarding live deliveries.
 * Used by {@link JobHandle#events()} so callers subscribing after a job has
 * already emitted still see the full event history.
 */
final class ReplayingPublisher<T> implements Flow.Publisher<T> {

    private final List<T> buffer = new CopyOnWriteArrayList<>();
    private final SubmissionPublisher<T> live;
    private volatile boolean closed;

    ReplayingPublisher() {
        this.live = new SubmissionPublisher<>(
                Executors.newVirtualThreadPerTaskExecutor(), 1024);
    }

    synchronized void submit(T item) {
        buffer.add(item);
        live.submit(item);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        live.close();
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> downstream) {
        final List<T> snapshot;
        final boolean wasClosed;
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // Hold the publisher lock while snapshotting AND attaching to live so
        // no submit() can interleave between the two and produce a gap.
        synchronized (this) {
            snapshot = new ArrayList<>(buffer);
            wasClosed = closed;
            if (!wasClosed) {
                live.subscribe(new Flow.Subscriber<T>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(T item) {
                        if (!cancelled.get()) {
                            downstream.onNext(item);
                        }
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
        }

        downstream.onSubscribe(new Flow.Subscription() {
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
        if (wasClosed) {
            downstream.onComplete();
        }
    }
}
