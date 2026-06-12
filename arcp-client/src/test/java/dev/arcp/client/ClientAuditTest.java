package dev.arcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.runtime.ArcpRuntime;
import dev.arcp.runtime.agent.JobOutcome;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClientAuditTest {

  @Test
  void subscribePublisherCompletesWhenJobTerminates() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  release.await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build();
    MemoryTransport.Pair pair = MemoryTransport.pair();
    runtime.accept(pair.runtime());
    try (ArcpClient client = ArcpClient.builder(pair.client()).build()) {
      client.connect(Duration.ofSeconds(5));
      JobHandle handle =
          client.submit(ArcpClient.jobSubmit("block@1.0.0", JsonNodeFactory.instance.objectNode()));
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

      CountDownLatch completed = new CountDownLatch(1);
      client
          .subscribe(handle.jobId(), SubscribeOptions.live())
          .subscribe(
              new Flow.Subscriber<EventBody>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                  s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(EventBody item) {}

                @Override
                public void onError(Throwable throwable) {
                  completed.countDown();
                }

                @Override
                public void onComplete() {
                  completed.countDown();
                }
              });

      release.countDown();
      handle.result().get(5, TimeUnit.SECONDS);
      // #105: the live subscriber publisher is completed when the job terminates.
      assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    }
    runtime.close();
  }

  @Test
  void listJobsBadCursorThrowsInvalidRequestNotTimeout() throws Exception {
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
    MemoryTransport.Pair pair = MemoryTransport.pair();
    runtime.accept(pair.runtime());
    try (ArcpClient client = ArcpClient.builder(pair.client()).build()) {
      client.connect(Duration.ofSeconds(5));
      // #102: a list_jobs error is correlated to the list request, surfacing INVALID_REQUEST
      // promptly rather than timing out (or failing an unrelated submit).
      assertThatThrownBy(() -> client.listJobs(null, 10, "#not-base64"))
          .isInstanceOfSatisfying(
              ArcpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
    runtime.close();
  }

  @Test
  void submitAsyncCompletesWithHandle() throws Exception {
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent("echo", "1.0.0", (input, ctx) -> JobOutcome.Success.inline(input.payload()))
            .build();
    MemoryTransport.Pair pair = MemoryTransport.pair();
    runtime.accept(pair.runtime());
    try (ArcpClient client = ArcpClient.builder(pair.client()).build()) {
      client.connect(Duration.ofSeconds(5));
      CompletableFuture<JobHandle> future =
          client.submitAsync(
              ArcpClient.jobSubmit("echo@1.0.0", JsonNodeFactory.instance.objectNode()));
      JobHandle handle = future.get(5, TimeUnit.SECONDS);
      assertThat(handle.jobId()).isNotNull();
      assertThat(handle.result().get(5, TimeUnit.SECONDS).finalStatus())
          .isEqualTo(dev.arcp.core.messages.JobResult.SUCCESS);
    }
    runtime.close();
  }

  @Test
  void crossSessionSubscriberObservesTermination() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  release.await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build();
    MemoryTransport.Pair submitPair = MemoryTransport.pair();
    MemoryTransport.Pair watchPair = MemoryTransport.pair();
    runtime.accept(submitPair.runtime());
    runtime.accept(watchPair.runtime());

    try (ArcpClient submitter = ArcpClient.builder(submitPair.client()).bearer("shared").build();
        ArcpClient watcher = ArcpClient.builder(watchPair.client()).bearer("shared").build()) {
      submitter.connect(Duration.ofSeconds(5));
      watcher.connect(Duration.ofSeconds(5));
      JobHandle handle =
          submitter.submit(
              ArcpClient.jobSubmit("block@1.0.0", JsonNodeFactory.instance.objectNode()));
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

      CountDownLatch watcherDone = new CountDownLatch(1);
      watcher
          .subscribe(handle.jobId(), SubscribeOptions.live())
          .subscribe(
              new Flow.Subscriber<EventBody>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                  s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(EventBody item) {}

                @Override
                public void onError(Throwable throwable) {
                  watcherDone.countDown();
                }

                @Override
                public void onComplete() {
                  watcherDone.countDown();
                }
              });

      // Give the subscribe time to register before completion.
      Thread.sleep(200);
      release.countDown();
      handle.result().get(5, TimeUnit.SECONDS);
      // #93 + #105: the cross-session subscriber observes the terminal message and its publisher
      // completes.
      assertThat(watcherDone.await(5, TimeUnit.SECONDS)).isTrue();
    }
    runtime.close();
  }
}
