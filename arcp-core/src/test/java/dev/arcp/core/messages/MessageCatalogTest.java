package dev.arcp.core.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.agents.AgentRef;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.Credential;
import dev.arcp.core.credentials.CredentialConstraints;
import dev.arcp.core.credentials.CredentialId;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.error.ArcpException;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.error.ErrorPayload;
import dev.arcp.core.error.UpstreamBudgetExhaustedException;
import dev.arcp.core.events.ArtifactRefEvent;
import dev.arcp.core.events.CredentialRotatedBody;
import dev.arcp.core.events.DelegateEvent;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.Events;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.events.ProgressEvent;
import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.events.ThoughtEvent;
import dev.arcp.core.events.ToolCallEvent;
import dev.arcp.core.events.ToolResultEvent;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.ResultId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.lease.Lease;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageCatalogTest {
  private static final ObjectMapper MAPPER = ArcpMapper.shared();
  private static final Instant NOW = Instant.parse("2026-05-25T12:00:00Z");
  private static final JobId JOB_ID = JobId.of("job_01HZY000000000000000000000");
  private static final TraceId TRACE_ID = TraceId.of("0123456789abcdef0123456789abcdef");
  private static final ResultId RESULT_ID = ResultId.of("res_01HZY000000000000000000000");
  private static final Lease LEASE =
      Lease.builder().allow("fs.read", "/workspace/**").allow("cost.budget", "usd:10.50").build();
  private static final LeaseConstraints CONSTRAINTS =
      LeaseConstraints.of(Instant.parse("2026-05-25T13:00:00Z"));

  @Test
  void everyMessageKindRoundTripsThroughEnvelopePayload() {
    List<Message> messages =
        List.of(
            new SessionHello(
                new ClientInfo("coverage-client", "1.0.0"),
                Auth.bearer("token"),
                new Capabilities(List.of("json"), EnumSet.of(Feature.SUBSCRIBE), null),
                "resume-token",
                41L),
            new SessionWelcome(
                new RuntimeInfo("coverage-runtime", "1.0.0"),
                "resume-token",
                600,
                30,
                new Capabilities(
                    List.of("json"),
                    EnumSet.of(Feature.SUBSCRIBE, Feature.LIST_JOBS),
                    List.of(new AgentDescriptor("echo", List.of("1.0.0", "2.0.0"), "2.0.0")))),
            new SessionBye("done"),
            new SessionClosed("done"),
            new SessionPing("nonce-1", NOW),
            new SessionPong("nonce-1", NOW.plusMillis(5)),
            new SessionAck(42L),
            new SessionListJobs(
                new JobFilter(List.of("success"), "echo", NOW.minusSeconds(5)), 10, "MTA"),
            new SessionJobs(
                MessageId.of("msg_list"),
                List.of(
                    new JobSummary(
                        JOB_ID, "echo@1.0.0", "success", LEASE, null, NOW, TRACE_ID, 3L)),
                "MjA"),
            new JobSubmit(
                AgentRef.parse("echo@1.0.0"),
                JsonNodeFactory.instance.objectNode().put("prompt", "hello"),
                LEASE,
                CONSTRAINTS,
                "idem-1",
                30),
            new JobAccepted(
                JOB_ID,
                "echo@1.0.0",
                LEASE,
                CONSTRAINTS,
                Map.of("usd", new BigDecimal("10.5")),
                List.of(
                    new Credential(
                        CredentialId.of("cred_1"),
                        CredentialScheme.BEARER,
                        "secret",
                        "https://api.example.test",
                        "default",
                        new CredentialConstraints(
                            List.of("usd:10.50"), List.of("gpt-4.1"), CONSTRAINTS.expiresAt()))),
                NOW,
                TRACE_ID),
            new JobEvent(
                EventBody.Kind.LOG.wire(),
                NOW,
                Events.encode(MAPPER, new LogEvent("info", "hello"))),
            new JobResult(JobResult.SUCCESS, RESULT_ID, 12L, null, "stored result"),
            JobError.fromJson(
                JobError.ERROR,
                ErrorCode.PERMISSION_DENIED,
                "not allowed",
                null,
                JsonNodeFactory.instance.objectNode().put("path", "/etc/passwd")),
            new JobCancel("user requested"),
            new JobCancelled("user requested"),
            new JobSubscribe(JOB_ID, 3L, true),
            new JobSubscribed(JOB_ID, "running", "echo@1.0.0", LEASE, null, TRACE_ID, 3L, true),
            new JobUnsubscribe(JOB_ID));

    assertThat(messages).hasSize(Message.Type.values().length);
    for (Message message : messages) {
      Envelope envelope =
          new Envelope(
              Envelope.VERSION,
              MessageId.generate(),
              message.kind().wire(),
              SessionId.of("sess_01HZY000000000000000000000"),
              TRACE_ID,
              JOB_ID,
              1L,
              Messages.encodePayload(MAPPER, message));

      assertThat(Message.Type.fromWire(message.kind().wire())).isEqualTo(message.kind());
      assertThat(Messages.decode(MAPPER, envelope)).isEqualTo(message);
    }
  }

  @Test
  void everyEventKindRoundTripsThroughEventDecoder() {
    List<EventBody> bodies =
        List.of(
            new LogEvent("info", "log line"),
            new ThoughtEvent("considering"),
            new ToolCallEvent(
                "search", JsonNodeFactory.instance.objectNode().put("query", "coverage"), "call-1"),
            new ToolResultEvent(
                "call-1",
                JsonNodeFactory.instance.objectNode().put("ok", true),
                ErrorPayload.of(ErrorCode.TIMEOUT, "retry later")),
            new StatusEvent(
                "running", "half way", JsonNodeFactory.instance.objectNode().put("pct", 50)),
            new MetricEvent(
                "cost.tokens", new BigDecimal("123.4"), "tokens", Map.of("model", "test")),
            new ArtifactRefEvent("file:///tmp/result.txt", "text/plain", 12L, "abc123"),
            new DelegateEvent(JOB_ID, "worker@1.0.0"),
            new ProgressEvent(5, 10L, "items", "half"),
            new ResultChunkEvent(RESULT_ID, 0, "hello", ResultChunkEvent.UTF8, false));

    assertThat(bodies).hasSize(EventBody.Kind.values().length);
    for (EventBody body : bodies) {
      assertThat(EventBody.Kind.fromWire(body.kind().wire())).isEqualTo(body.kind());
      assertThat(Events.decode(MAPPER, body.kind().wire(), Events.encode(MAPPER, body)))
          .isEqualTo(body);
    }
  }

  @Test
  void identifiersErrorsAndLeaseHelpersExercisePublicContracts() {
    assertThat(JobId.generate().value()).startsWith("job_");
    assertThat(ResultId.generate().value()).startsWith("res_");
    assertThat(SessionId.generate().value()).startsWith("sess_");
    assertThat(MessageId.generate().value()).isNotBlank();
    assertThat(TraceId.generate().value()).hasSize(32);
    assertThat(AgentRef.parse("echo@1.0.0").versionOpt()).contains("1.0.0");
    assertThat(Feature.fromWire("future")).isEmpty();
    assertThat(ErrorCode.fromWire("NO_SUCH_CODE")).isEqualTo(ErrorCode.INTERNAL_ERROR);
    assertThat(new CredentialRotatedBody(CredentialId.of("cred_rotated"), "next").value())
        .isEqualTo("next");
    assertThat(new UpstreamBudgetExhaustedException("limited", "body").upstreamResponseBody())
        .isEqualTo("body");
    assertThat(LEASE.patterns("fs.read")).containsExactly("/workspace/**");
    assertThat(LEASE.budget()).containsEntry("usd", new BigDecimal("10.50"));
    assertThat(LEASE).hasToString("Lease{fs.read=[/workspace/**], cost.budget=[usd:10.50]}");

    for (ErrorCode code : ErrorCode.values()) {
      ArcpException exception = ArcpException.from(ErrorPayload.of(code, code.name()));
      assertThat(exception.code()).isEqualTo(code);
      assertThat(exception.retryable()).isEqualTo(code.retryable());
      assertThat(exception.getMessage()).isEqualTo(code.name());
    }

    assertThatThrownBy(() -> Message.Type.fromWire("missing.type"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown message type");
    assertThatThrownBy(() -> EventBody.Kind.fromWire("missing_event"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown event kind");
    assertThatThrownBy(() -> new ProgressEvent(-1, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResultChunkEvent(RESULT_ID, 0, "x", "ascii", false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
