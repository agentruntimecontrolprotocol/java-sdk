package dev.arcp.core.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.auth.BearerVerifier;
import dev.arcp.core.auth.Principal;
import dev.arcp.core.credentials.CredentialConstraints;
import dev.arcp.core.error.UnauthenticatedException;
import dev.arcp.core.events.EventBody;
import dev.arcp.core.events.MetricEvent;
import dev.arcp.core.events.ProgressEvent;
import dev.arcp.core.events.ResultChunkEvent;
import dev.arcp.core.events.StatusEvent;
import dev.arcp.core.ids.ResultId;
import dev.arcp.core.lease.LeaseConstraints;
import dev.arcp.core.wire.ArcpMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Branch coverage for event-body validation, credential/lease constraints, and bearer auth. */
class EventAndConstraintCoverageTest {

  private final ObjectMapper mapper = ArcpMapper.shared();

  @Test
  void progressEventValidatesBounds() {
    ProgressEvent withTotal = new ProgressEvent(5, 10L, "files", "halfway");
    assertThat(withTotal.kind()).isEqualTo(EventBody.Kind.PROGRESS);
    ProgressEvent withoutTotal = new ProgressEvent(0, null, null, null);
    assertThat(withoutTotal.total()).isNull();
    assertThatThrownBy(() -> new ProgressEvent(-1, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current");
    assertThatThrownBy(() -> new ProgressEvent(0, -1L, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("total");
  }

  @Test
  void resultChunkEventValidatesSeqAndEncoding() {
    ResultId rid = ResultId.of("r1");
    assertThat(new ResultChunkEvent(rid, 0, "hi", ResultChunkEvent.UTF8, true).kind())
        .isEqualTo(EventBody.Kind.RESULT_CHUNK);
    assertThatCode(() -> new ResultChunkEvent(rid, 1, "aGk=", ResultChunkEvent.BASE64, false))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new ResultChunkEvent(rid, -1, "x", ResultChunkEvent.UTF8, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunk_seq");
    assertThatThrownBy(() -> new ResultChunkEvent(rid, 0, "x", "hex", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("encoding");
  }

  @Test
  void metricEventCopiesDimensions() {
    MetricEvent bare = new MetricEvent("latency", new BigDecimal("1.5"), null, null);
    assertThat(bare.dimensions()).isNull();
    assertThat(bare.kind()).isEqualTo(EventBody.Kind.METRIC);
    MetricEvent dimensioned =
        new MetricEvent("latency", BigDecimal.ONE, "ms", Map.of("region", "us"));
    assertThat(dimensioned.dimensions()).containsEntry("region", "us");
  }

  @Test
  void statusEventTwoArgConstructorOmitsDetails() {
    StatusEvent status = new StatusEvent("running", "warming up");
    assertThat(status.details()).isNull();
    assertThat(status.kind()).isEqualTo(EventBody.Kind.STATUS);
  }

  @Test
  void credentialConstraintsCopiesListsAndAllowsNulls() {
    CredentialConstraints empty = new CredentialConstraints(null, null, null);
    assertThat(empty.costBudget()).isNull();
    assertThat(empty.modelUse()).isNull();
    CredentialConstraints full =
        new CredentialConstraints(List.of("usd:1"), List.of("gpt*"), Instant.EPOCH);
    assertThat(full.costBudget()).containsExactly("usd:1");
    assertThat(full.modelUse()).containsExactly("gpt*");
  }

  @Test
  void leaseConstraintsFactoriesAndJsonDefaults() throws Exception {
    assertThat(LeaseConstraints.none().expiresAt()).isNull();
    Instant expiry = Instant.parse("2030-01-01T00:00:00Z");
    assertThat(LeaseConstraints.of(expiry).expiresAtJson()).isEqualTo(expiry);
    assertThat(mapper.readValue("{}", LeaseConstraints.class)).isEqualTo(LeaseConstraints.none());
    LeaseConstraints parsed =
        mapper.readValue("{\"expires_at\":\"2030-01-01T00:00:00Z\"}", LeaseConstraints.class);
    assertThat(parsed.expiresAt()).isEqualTo(expiry);
  }

  @Test
  void parseStrictUtcAcceptsOnlyZSuffix() {
    assertThat(LeaseConstraints.parseStrictUtc("2030-01-01T00:00:00Z"))
        .isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    assertThatThrownBy(() -> LeaseConstraints.parseStrictUtc("2030-01-01T00:00:00+02:00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be UTC");
    assertThatThrownBy(() -> LeaseConstraints.parseStrictUtc("2030-01-01T00:00:00+00:00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Z suffix");
    assertThatThrownBy(() -> LeaseConstraints.parseStrictUtc("not-a-date"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid expires_at");
  }

  @Test
  void staticTokenVerifierComparesInConstantTime() throws Exception {
    Principal alice = new Principal("alice");
    BearerVerifier verifier = BearerVerifier.staticToken("hunter2", alice);
    assertThat(verifier.verify("hunter2")).isEqualTo(alice);
    assertThatThrownBy(() -> verifier.verify("hunter3"))
        .isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> verifier.verify("short")).isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(UnauthenticatedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tok-a", "tok-b"})
  void acceptAnyDerivesStablePrincipalFromDigest(String token) throws Exception {
    BearerVerifier verifier = BearerVerifier.acceptAny();
    Principal first = verifier.verify(token);
    assertThat(first.id()).startsWith("bearer:").hasSize("bearer:".length() + 32);
    assertThat(verifier.verify(token)).isEqualTo(first);
  }

  @Test
  void acceptAnyRejectsMissingTokens() {
    BearerVerifier verifier = BearerVerifier.acceptAny();
    assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> verifier.verify("")).isInstanceOf(UnauthenticatedException.class);
  }
}
