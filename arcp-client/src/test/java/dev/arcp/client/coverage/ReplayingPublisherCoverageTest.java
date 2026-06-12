package dev.arcp.client.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Branch coverage for the package-private {@code ReplayingPublisher}: replay-then-live handoff,
 * cancellation during snapshot replay and pending-drain, close idempotency, and error forwarding.
 * The class is reached via reflection because these tests live outside {@code dev.arcp.client}.
 */
class ReplayingPublisherCoverageTest {

  /** Reflection facade over one ReplayingPublisher&lt;Object&gt; instance. */
  private static final class Harness {
    final Object instance;
    private final Method submitMethod;

    Harness() throws Exception {
      Class<?> cls = Class.forName("dev.arcp.client.ReplayingPublisher");
      Constructor<?> ctor = cls.getDeclaredConstructor();
      ctor.setAccessible(true);
      instance = ctor.newInstance();
      submitMethod = cls.getDeclaredMethod("submit", Object.class);
      submitMethod.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    Flow.Publisher<Object> publisher() {
      return (Flow.Publisher<Object>) instance;
    }

    void submit(Object item) throws Exception {
      submitMethod.invoke(instance, item);
    }

    void close() throws Exception {
      ((AutoCloseable) instance).close();
    }

    @SuppressWarnings("unchecked")
    SubmissionPublisher<Object> live() throws Exception {
      Field field = instance.getClass().getDeclaredField("live");
      field.setAccessible(true);
      return (SubmissionPublisher<Object>) field.get(instance);
    }

    ExecutorService liveExecutor() throws Exception {
      Field field = instance.getClass().getDeclaredField("liveExecutor");
      field.setAccessible(true);
      return (ExecutorService) field.get(instance);
    }

    /** Close, then wait for the forwarder tasks to finish so their branches are executed. */
    void closeAndDrain() throws Exception {
      close();
      assertThat(liveExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static class Collector implements Flow.Subscriber<Object> {
    final CopyOnWriteArrayList<Object> received = new CopyOnWriteArrayList<>();
    final CountDownLatch completed = new CountDownLatch(1);
    final CountDownLatch errored = new CountDownLatch(1);
    volatile Flow.Subscription subscription;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(Object item) {
      received.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
      errored.countDown();
    }

    @Override
    public void onComplete() {
      completed.countDown();
    }
  }

  @Test
  void replayThenLiveHandoffPreservesOrderAndCompletes() throws Exception {
    Harness harness = new Harness();
    harness.submit("buffered-1");
    harness.submit("buffered-2");
    Collector collector = new Collector();
    harness.publisher().subscribe(collector);
    // Replay happens synchronously inside subscribe().
    assertThat(collector.received).containsExactly("buffered-1", "buffered-2");
    harness.submit("live-1");
    org.awaitility.Awaitility.await()
        .atMost(3, TimeUnit.SECONDS)
        .until(() -> collector.received.size() == 3);
    assertThat(collector.received).containsExactly("buffered-1", "buffered-2", "live-1");
    harness.closeAndDrain();
    assertThat(collector.completed.await(3, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void closeIsIdempotentAndLateSubscribersStillGetReplayThenComplete() throws Exception {
    Harness harness = new Harness();
    harness.submit("only");
    harness.closeAndDrain();
    harness.close(); // second close hits the already-closed fast path
    Collector late = new Collector();
    harness.publisher().subscribe(late);
    // wasClosed path: replay and completion are delivered synchronously.
    assertThat(late.received).containsExactly("only");
    assertThat(late.completed.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void cancellingDuringSnapshotReplayStopsDelivery() throws Exception {
    Harness harness = new Harness();
    harness.submit("a");
    harness.submit("b");
    harness.submit("c");
    Collector cancelling =
        new Collector() {
          @Override
          public void onNext(Object item) {
            super.onNext(item);
            subscription.cancel();
          }
        };
    harness.publisher().subscribe(cancelling);
    assertThat(cancelling.received).containsExactly("a");
    harness.closeAndDrain();
    // A cancelled downstream must not observe completion.
    assertThat(cancelling.completed.await(150, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  void liveItemsDuringReplayAreBufferedAndDrainedInOrder() throws Exception {
    int liveCount = 1500; // > SubmissionPublisher buffer (1024): guarantees pending-drain traffic
    Harness harness = new Harness();
    harness.submit("seed");
    CountDownLatch seedSeen = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Collector collector =
        new Collector() {
          @Override
          public void onNext(Object item) {
            super.onNext(item);
            if ("seed".equals(item)) {
              seedSeen.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          }
        };
    Thread subscribing = new Thread(() -> harness.publisher().subscribe(collector));
    subscribing.start();
    assertThat(seedSeen.await(3, TimeUnit.SECONDS)).isTrue();
    // While replay is parked inside onNext("seed"), live submissions overflow the forwarder's
    // buffer, forcing it to consume into the pending deque (replayDone is still false).
    for (int i = 0; i < liveCount; i++) {
      harness.submit("live-" + i);
    }
    release.countDown();
    subscribing.join(TimeUnit.SECONDS.toMillis(10));
    assertThat(subscribing.isAlive()).isFalse();
    org.awaitility.Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> collector.received.size() == liveCount + 1);
    // Order is preserved across replay -> pending drain -> direct live delivery.
    assertThat(collector.received.get(0)).isEqualTo("seed");
    for (int i = 0; i < liveCount; i++) {
      assertThat(collector.received.get(i + 1)).isEqualTo("live-" + i);
    }
    harness.closeAndDrain();
    assertThat(collector.completed.await(3, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void cancellingDuringPendingDrainStopsDeliveryAndSuppressesCompletion() throws Exception {
    int liveCount = 1500;
    int cancelAt = 100; // well inside the guaranteed-pending range (>= 476 buffered entries)
    Harness harness = new Harness();
    harness.submit("seed");
    CountDownLatch seedSeen = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger delivered = new AtomicInteger();
    Collector collector =
        new Collector() {
          @Override
          public void onNext(Object item) {
            super.onNext(item);
            int count = delivered.incrementAndGet();
            if ("seed".equals(item)) {
              seedSeen.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            } else if (count == cancelAt) {
              subscription.cancel();
            }
          }
        };
    Thread subscribing = new Thread(() -> harness.publisher().subscribe(collector));
    subscribing.start();
    assertThat(seedSeen.await(3, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < liveCount; i++) {
      harness.submit("live-" + i);
    }
    release.countDown();
    subscribing.join(TimeUnit.SECONDS.toMillis(10));
    assertThat(subscribing.isAlive()).isFalse();
    // The drain loop stops at the cancellation point; later live items hit the forwarder's
    // cancelled guard instead of the downstream.
    assertThat(collector.received).hasSize(cancelAt);
    harness.submit("post-cancel");
    harness.closeAndDrain();
    assertThat(collector.received).hasSize(cancelAt);
    assertThat(collector.completed.await(150, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  void errorsForwardToActiveSubscribersButNotCancelledOnes() throws Exception {
    Harness harness = new Harness();
    Collector active = new Collector();
    Collector cancelled =
        new Collector() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            super.onSubscribe(subscription);
            subscription.cancel();
          }
        };
    harness.publisher().subscribe(active);
    harness.publisher().subscribe(cancelled);
    harness.live().closeExceptionally(new IllegalStateException("upstream torn down"));
    assertThat(active.errored.await(3, TimeUnit.SECONDS)).isTrue();
    ExecutorService executor = harness.liveExecutor();
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(cancelled.errored.getCount()).isEqualTo(1);
    assertThat(List.copyOf(cancelled.received)).isEmpty();
  }
}
