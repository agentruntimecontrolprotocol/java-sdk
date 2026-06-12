package dev.arcp.core.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.arcp.core.auth.Auth;
import dev.arcp.core.capabilities.AgentDescriptor;
import dev.arcp.core.capabilities.Capabilities;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.credentials.CredentialScheme;
import dev.arcp.core.error.ErrorCode;
import dev.arcp.core.error.ErrorPayload;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.ids.JobId;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.ids.ResultId;
import dev.arcp.core.ids.SessionId;
import dev.arcp.core.ids.TraceId;
import dev.arcp.core.messages.ClientInfo;
import dev.arcp.core.messages.JobFilter;
import dev.arcp.core.messages.Message;
import dev.arcp.core.messages.Messages;
import dev.arcp.core.messages.SessionAck;
import dev.arcp.core.messages.SessionHello;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Branch coverage for wire-level value types: capabilities, envelopes, payload codecs, enums. */
class CoreWireCoverageTest {

  private final ObjectMapper mapper = ArcpMapper.shared();

  @Test
  void capabilitiesConstructorAppliesDefaults() {
    Capabilities caps = new Capabilities(null, null, null);
    assertThat(caps.encodings()).containsExactly("json");
    assertThat(caps.features()).isEmpty();
    assertThat(caps.agents()).isNull();
    assertThat(caps.featuresWire()).isEmpty();
  }

  @Test
  void capabilitiesConstructorCopiesProvidedValues() {
    Capabilities caps =
        new Capabilities(
            List.of("json"),
            Set.of(Feature.ACK),
            List.of(new AgentDescriptor("echo", List.of("1.0.0"), "1.0.0")));
    assertThat(caps.encodings()).containsExactly("json");
    assertThat(caps.features()).containsExactly(Feature.ACK);
    assertThat(caps.agents()).hasSize(1);
  }

  @Test
  void capabilitiesFromJsonDropsUnknownFeatures() throws Exception {
    Capabilities caps =
        mapper.readValue("{\"features\":[\"heartbeat\",\"mystery\"]}", Capabilities.class);
    assertThat(caps.features()).containsExactly(Feature.HEARTBEAT);
    assertThat(caps.encodings()).containsExactly("json");

    Capabilities defaults = mapper.readValue("{}", Capabilities.class);
    assertThat(defaults.features()).isEmpty();
    assertThat(defaults.encodings()).containsExactly("json");

    Capabilities explicit =
        mapper.readValue("{\"encodings\":[\"json\",\"cbor\"],\"features\":[]}", Capabilities.class);
    assertThat(explicit.encodings()).containsExactly("json", "cbor");
  }

  @Test
  void capabilitiesIntersectHandlesEmptyAndNonEmptyOverlap() {
    Set<Feature> ackOnly =
        Capabilities.intersect(EnumSet.of(Feature.HEARTBEAT, Feature.ACK), EnumSet.of(Feature.ACK));
    assertThat(ackOnly).containsExactly(Feature.ACK);
    Set<Feature> none =
        Capabilities.intersect(EnumSet.of(Feature.HEARTBEAT), EnumSet.of(Feature.ACK));
    assertThat(none).isEmpty();
  }

  @Test
  void featuresWireIsSorted() {
    Capabilities caps = Capabilities.of(EnumSet.of(Feature.HEARTBEAT, Feature.ACK));
    assertThat(caps.featuresWire()).containsExactly("ack", "heartbeat");
  }

  @Test
  void envelopeBuilderCarriesConditionalFields() {
    Envelope env =
        Envelope.builder("session.ping")
            .id(MessageId.of("m1"))
            .sessionId(SessionId.of("s1"))
            .traceId(TraceId.of("t1"))
            .jobId(JobId.of("j1"))
            .eventSeq(7L)
            .payload(JsonNodeFactory.instance.objectNode())
            .build();
    assertThat(env.arcp()).isEqualTo(Envelope.VERSION);
    assertThat(env.id()).isEqualTo(MessageId.of("m1"));
    assertThat(env.sessionId()).isEqualTo(SessionId.of("s1"));
    assertThat(env.traceId()).isEqualTo(TraceId.of("t1"));
    assertThat(env.jobId()).isEqualTo(JobId.of("j1"));
    assertThat(env.eventSeq()).isEqualTo(7L);
  }

  @Test
  void envelopeBuilderRequiresPayload() {
    assertThatThrownBy(() -> Envelope.builder("session.ping").build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void encodePayloadRejectsNonObjectTrees() {
    ObjectMapper custom = new ObjectMapper();
    SimpleModule module = new SimpleModule();
    module.addSerializer(
        SessionAck.class,
        new StdSerializer<>(SessionAck.class) {
          @Override
          public void serialize(SessionAck value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeString("not-an-object");
          }
        });
    custom.registerModule(module);
    assertThatThrownBy(() -> Messages.encodePayload(custom, new SessionAck(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be an object");
  }

  @Test
  void errorPayloadDefaultsRetryabilityFromCode() throws Exception {
    ErrorPayload defaulted =
        mapper.readValue("{\"code\":\"TIMEOUT\",\"message\":\"m\"}", ErrorPayload.class);
    assertThat(defaulted.retryable()).isTrue();
    ErrorPayload explicit =
        mapper.readValue(
            "{\"code\":\"TIMEOUT\",\"message\":\"m\",\"retryable\":false}", ErrorPayload.class);
    assertThat(explicit.retryable()).isFalse();
    assertThat(ErrorPayload.of(ErrorCode.CANCELLED, "stop").retryable()).isFalse();
  }

  @Test
  void errorCodeWireMapping() {
    assertThat(ErrorCode.fromWire("TIMEOUT")).isEqualTo(ErrorCode.TIMEOUT);
    assertThat(ErrorCode.fromWire("NOT_A_CODE")).isEqualTo(ErrorCode.INTERNAL_ERROR);
    assertThat(ErrorCode.TIMEOUT.wire()).isEqualTo("TIMEOUT");
    assertThat(ErrorCode.HEARTBEAT_LOST.retryable()).isTrue();
    assertThat(ErrorCode.PERMISSION_DENIED.retryable()).isFalse();
  }

  @Test
  void credentialSchemeWireMapping() {
    assertThat(CredentialScheme.fromWire("bearer")).isEqualTo(CredentialScheme.BEARER);
    assertThat(CredentialScheme.fromWire("signed_url")).isEqualTo(CredentialScheme.UNKNOWN);
    assertThat(CredentialScheme.BEARER.isBearer()).isTrue();
    assertThat(CredentialScheme.UNKNOWN.isBearer()).isFalse();
    assertThat(CredentialScheme.BEARER.wire()).isEqualTo("bearer");
  }

  @Test
  void sessionHelloDefaultsMissingCapabilities() {
    SessionHello hello =
        new SessionHello(new ClientInfo("c", "1"), Auth.anonymous(), null, null, null);
    assertThat(hello.capabilities().features()).isEmpty();
    assertThat(hello.kind()).isEqualTo(Message.Type.SESSION_HELLO);
  }

  @Test
  void agentDescriptorDefaultsVersions() {
    AgentDescriptor descriptor = new AgentDescriptor("echo", null, null);
    assertThat(descriptor.versions()).isEmpty();
    AgentDescriptor versioned = new AgentDescriptor("echo", List.of("1.0.0"), "1.0.0");
    assertThat(versioned.versions()).containsExactly("1.0.0");
  }

  @Test
  void jobFilterCopiesStatusAndSupportsAll() {
    JobFilter filter = new JobFilter(List.of("running"), "echo", Instant.EPOCH);
    assertThat(filter.status()).containsExactly("running");
    assertThat(JobFilter.all().status()).isNull();
  }

  @Test
  void idsExposeWireValueThroughToString() {
    assertThat(JobId.of("j1")).hasToString("j1");
    assertThat(JobId.generate().asString()).startsWith("job_");
    assertThat(MessageId.of("m1")).hasToString("m1");
    assertThat(SessionId.of("s1")).hasToString("s1");
    assertThat(ResultId.of("r1")).hasToString("r1");
    assertThat(TraceId.of("t1")).hasToString("t1");
  }

  @Test
  void authFactories() {
    assertThat(Auth.anonymous().scheme()).isEqualTo(Auth.ANONYMOUS);
    assertThat(Auth.anonymous().token()).isNull();
    assertThat(Auth.bearer("tok").scheme()).isEqualTo(Auth.BEARER);
  }

  @Test
  void messageTypeWireMappingIncludesLegacyByeAlias() {
    assertThat(Message.Type.fromWire("session.close")).isEqualTo(Message.Type.SESSION_BYE);
    assertThat(Message.Type.fromWire("session.bye")).isEqualTo(Message.Type.SESSION_BYE);
    assertThatThrownBy(() -> Message.Type.fromWire("session.nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown message type");
  }

  @Test
  void eventKindWireMappingRejectsUnknown() {
    assertThat(EventBody.Kind.fromWire("log")).isEqualTo(EventBody.Kind.LOG);
    assertThatThrownBy(() -> EventBody.Kind.fromWire("nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown event kind");
  }
}
