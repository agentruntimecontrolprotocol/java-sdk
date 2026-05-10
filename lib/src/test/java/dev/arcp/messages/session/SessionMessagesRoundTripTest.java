package dev.arcp.messages.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.auth.Credentials;
import dev.arcp.auth.Identity;
import dev.arcp.capability.Capabilities;
import dev.arcp.envelope.ARCPMapper;
import dev.arcp.envelope.Envelope;
import dev.arcp.envelope.MessageType;
import dev.arcp.error.ErrorCode;
import dev.arcp.ids.MessageId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionMessagesRoundTripTest {

	private final ObjectMapper mapper = ARCPMapper.create();

	private Envelope wrap(MessageType payload) {
		return Envelope.of(MessageId.random(), Instant.parse("2026-05-10T00:00:00Z"), payload);
	}

	private Envelope round(MessageType payload) throws Exception {
		return mapper.readValue(mapper.writeValueAsString(wrap(payload)), Envelope.class);
	}

	@Test
	void sessionOpenRoundTrip() throws Exception {
		SessionMessages.SessionOpen open = new SessionMessages.SessionOpen("1.0",
				new Credentials.BearerCredentials("t"), new Identity("client", "1.0", null, "trusted"),
				Capabilities.reference());
		assertThat(round(open).payload()).isEqualTo(wrap(open).payload());
	}

	@Test
	void sessionAcceptedRoundTrip() throws Exception {
		SessionMessages.SessionAccepted acc = new SessionMessages.SessionAccepted(
				new Identity("rt", "0.1.0", null, "trusted"), Capabilities.reference(), null);
		assertThat(round(acc).payload()).isEqualTo(wrap(acc).payload());
	}

	@Test
	void sessionRejectedRoundTrip() throws Exception {
		SessionMessages.SessionRejected r = new SessionMessages.SessionRejected(ErrorCode.UNIMPLEMENTED, "nope");
		assertThat(round(r).payload()).isEqualTo(wrap(r).payload());
	}

	@Test
	void sessionCloseWithoutReason() throws Exception {
		SessionMessages.SessionClose c = new SessionMessages.SessionClose(null);
		assertThat(round(c).payload()).isEqualTo(wrap(c).payload());
	}

	@Test
	void jwtCredentialsRoundTrip() throws Exception {
		SessionMessages.SessionOpen open = new SessionMessages.SessionOpen("1.0",
				new Credentials.JwtCredentials("eyJxxx"), null, Capabilities.NONE);
		Envelope back = round(open);
		SessionMessages.SessionOpen o = (SessionMessages.SessionOpen) back.payload();
		assertThat(o.credentials()).isInstanceOf(Credentials.JwtCredentials.class);
	}

	@Test
	void noneCredentialsRoundTrip() throws Exception {
		SessionMessages.SessionOpen open = new SessionMessages.SessionOpen("1.0", new Credentials.NoneCredentials(),
				null, Capabilities.NONE);
		Envelope back = round(open);
		SessionMessages.SessionOpen o = (SessionMessages.SessionOpen) back.payload();
		assertThat(o.credentials()).isInstanceOf(Credentials.NoneCredentials.class);
	}
}
