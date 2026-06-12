package dev.arcp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.JobAccepted;
import dev.arcp.core.messages.JobError;
import dev.arcp.core.messages.JobEvent;
import dev.arcp.core.messages.JobResult;
import dev.arcp.core.messages.JobSubmit;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.SessionBye;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.messages.SessionWelcome;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import dev.arcp.runtime.agent.JobOutcome;
import dev.arcp.runtime.session.SessionLoop;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** §6.3 session resume coverage (#22). */
class SessionResumeTest {
  private static final ObjectMapper MAPPER = ArcpMapper.shared();
  private static final SessionId CLIENT_SESSION = SessionId.of("sess_client");

  @Test
  void resumeReattachesReplaysAndContinues() throws Exception {
    CountDownLatch gate1 = new CountDownLatch(1);
    CountDownLatch gate2 = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "stream",
                "1.0.0",
                (input, ctx) -> {
                  ctx.emit(new LogEvent("info", "e1"));
                  gate1.await();
                  ctx.emit(new LogEvent("info", "e2"));
                  gate2.await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {

      MemoryTransport.Pair pair1 = MemoryTransport.pair();
      Probe probe1 = new Probe();
      pair1.client().incoming().subscribe(probe1);
      SessionLoop loop = runtime.accept(pair1.runtime());

      SessionWelcome welcome = handshake(pair1.client(), probe1);
      String resumeToken = welcome.resumeToken();
      assertThat(resumeToken).isNotNull();

      send(
          pair1.client(),
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("stream@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      probe1.take(Message.Type.JOB_ACCEPTED);
      Envelope e1 = probe1.take(Message.Type.JOB_EVENT);
      long lastSeq = e1.eventSeq();

      // Simulate an unexpected transport drop (server endpoint closes; no session.close was sent).
      pair1.runtime().close();
      assertThat(awaitPhase(loop, SessionLoop.Phase.PARKED)).isTrue();

      // The still-running job emits another event while parked; it is buffered for replay.
      gate1.countDown();

      // Reconnect with the saved token and last seen sequence.
      MemoryTransport.Pair pair2 = MemoryTransport.pair();
      Probe probe2 = new Probe();
      pair2.client().incoming().subscribe(probe2);
      runtime.accept(pair2.runtime());
      sendHello(pair2.client(), resumeToken, lastSeq);

      SessionWelcome resumed =
          probe2.takeMessage(Message.Type.SESSION_WELCOME, SessionWelcome.class);
      assertThat(resumed.resumeToken()).isEqualTo(resumeToken);

      // Replay: the missed event (e2) is delivered on the new transport.
      JobEvent replayed = probe2.takeMessage(Message.Type.JOB_EVENT, JobEvent.class);
      assertThat(replayed.eventKind()).isEqualTo("log");

      // Live continuation: the job finishes and its result lands on the resumed transport.
      gate2.countDown();
      JobResult result = probe2.takeMessage(Message.Type.JOB_RESULT, JobResult.class);
      assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
    }
  }

  @Test
  void explicitCloseCancelsAndIsNotResumable() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ArcpRuntime runtime =
        ArcpRuntime.builder()
            .agent(
                "block",
                "1.0.0",
                (input, ctx) -> {
                  started.countDown();
                  release.await();
                  return JobOutcome.Success.inline(input.payload());
                })
            .build()) {
      MemoryTransport.Pair pair1 = MemoryTransport.pair();
      Probe probe1 = new Probe();
      pair1.client().incoming().subscribe(probe1);
      runtime.accept(pair1.runtime());
      SessionWelcome welcome = handshake(pair1.client(), probe1);

      send(
          pair1.client(),
          Message.Type.JOB_SUBMIT,
          new JobSubmit(
              AgentRef.parse("block@1.0.0"),
              JsonNodeFactory.instance.objectNode(),
              null,
              null,
              null,
              null));
      JobAccepted accepted = probe1.takeMessage(Message.Type.JOB_ACCEPTED, JobAccepted.class);
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

      // §6.7/#22: explicit close cancels in-flight jobs and is not resumable.
      send(pair1.client(), Message.Type.SESSION_BYE, new SessionBye("done"));
      probe1.take(Message.Type.SESSION_CLOSED);
      release.countDown();

      // The in-flight job is cancelled by the explicit close.
      long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (System.nanoTime() < deadline) {
        var rec = runtime.job(accepted.jobId());
        if (rec != null && rec.status().terminal()) {
          break;
        }
        Thread.sleep(10);
      }
      assertThat(runtime.job(accepted.jobId()).status().terminal()).isTrue();

      MemoryTransport.Pair pair2 = MemoryTransport.pair();
      Probe probe2 = new Probe();
      pair2.client().incoming().subscribe(probe2);
      runtime.accept(pair2.runtime());
      sendHello(pair2.client(), welcome.resumeToken(), 0L);
      JobError resumeError = probe2.takeMessage(Message.Type.JOB_ERROR, JobError.class);
      assertThat(resumeError.code()).isEqualTo(ErrorCode.RESUME_WINDOW_EXPIRED);
    }
  }

  private static boolean awaitPhase(SessionLoop loop, SessionLoop.Phase target)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < deadline) {
      if (loop.phase() == target) {
        return true;
      }
      Thread.sleep(10);
    }
    return loop.phase() == target;
  }

  private static SessionWelcome handshake(MemoryTransport client, Probe probe) throws Exception {
    sendHello(client, null, null);
    return probe.takeMessage(Message.Type.SESSION_WELCOME, SessionWelcome.class);
  }

  private static void sendHello(MemoryTransport client, String resumeToken, Long lastEventSeq) {
    send(
        client,
        Message.Type.SESSION_HELLO,
        new SessionHello(
            new ClientInfo("resume-test", "1.0.0"),
            // Bearer auth yields a stable principal across reconnects, which resume requires.
            Auth.bearer("resume-principal"),
            new Capabilities(List.of("json"), EnumSet.of(Feature.SUBSCRIBE), null),
            resumeToken,
            lastEventSeq));
  }

  private static void send(MemoryTransport client, Message.Type type, Message message) {
    client.send(
        new Envelope(
            Envelope.VERSION,
            MessageId.generate(),
            type.wire(),
            CLIENT_SESSION,
            null,
            null,
            null,
            Messages.encodePayload(MAPPER, message)));
  }

  private static final class Probe implements Flow.Subscriber<Envelope> {
    private final BlockingQueue<Envelope> envelopes = new LinkedBlockingQueue<>();
    private final Queue<Envelope> backlog = new ArrayDeque<>();

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(Envelope item) {
      envelopes.add(item);
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}

    <T extends Message> T takeMessage(Message.Type type, Class<T> messageClass) throws Exception {
      return messageClass.cast(Messages.decode(MAPPER, take(type)));
    }

    Envelope take(Message.Type type) throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline) {
        for (Envelope existing : List.copyOf(backlog)) {
          if (existing.type().equals(type.wire())) {
            backlog.remove(existing);
            return existing;
          }
        }
        long remaining = deadline - System.nanoTime();
        Envelope envelope = envelopes.poll(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
        if (envelope == null) {
          break;
        }
        if (envelope.type().equals(type.wire())) {
          return envelope;
        }
        backlog.add(envelope);
      }
      throw new AssertionError("timed out waiting for " + type.wire() + "; backlog=" + backlog);
    }
  }
}
