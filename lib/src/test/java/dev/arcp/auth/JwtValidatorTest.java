package dev.arcp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtValidatorTest {

	private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), java.time.ZoneOffset.UTC);

	private static String sign(String subject, String issuer, Instant exp, String trustLevel) throws Exception {
		JWSSigner signer = new MACSigner(KEY);
		JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject(subject).issuer(issuer)
				.expirationTime(Date.from(exp));
		if (trustLevel != null) {
			b.claim("trust_level", trustLevel);
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), b.build());
		jwt.sign(signer);
		return jwt.serialize();
	}

	@Test
	void happyPath() throws Exception {
		JwtValidator validator = new JwtValidator(KEY, "issuer-x", CLOCK);
		String jwt = sign("alice", "issuer-x", Instant.parse("2026-05-10T01:00:00Z"), "trusted");
		Principal p = validator.validate(new Credentials.JwtCredentials(jwt));
		assertThat(p.subject()).isEqualTo("alice");
		assertThat(p.trustLevel()).isEqualTo("trusted");
	}

	@Test
	void expiredRejected() throws Exception {
		JwtValidator validator = new JwtValidator(KEY, "issuer-x", CLOCK);
		String jwt = sign("alice", "issuer-x", Instant.parse("2026-05-09T00:00:00Z"), "trusted");
		assertThatThrownBy(() -> validator.validate(new Credentials.JwtCredentials(jwt)))
				.isInstanceOf(ARCPException.class)
				.satisfies(e -> assertThat(((ARCPException) e).code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
	}

	@Test
	void wrongIssuerRejected() throws Exception {
		JwtValidator validator = new JwtValidator(KEY, "issuer-x", CLOCK);
		String jwt = sign("alice", "other", Instant.parse("2026-05-10T01:00:00Z"), null);
		assertThatThrownBy(() -> validator.validate(new Credentials.JwtCredentials(jwt)))
				.isInstanceOf(ARCPException.class);
	}

	@Test
	void wrongCredentialKindRejected() {
		JwtValidator validator = new JwtValidator(KEY, "issuer-x", CLOCK);
		assertThatThrownBy(() -> validator.validate(new Credentials.BearerCredentials("x")))
				.isInstanceOf(ARCPException.class);
	}

	@Test
	void malformedJwtRejected() {
		JwtValidator validator = new JwtValidator(KEY, "issuer-x", CLOCK);
		assertThatThrownBy(() -> validator.validate(new Credentials.JwtCredentials("not-a-jwt")))
				.isInstanceOf(ARCPException.class);
	}

	@Test
	void anonymousPrincipalConstants() {
		assertThat(Principal.anonymous().subject()).isEqualTo("anonymous");
		assertThat(Principal.anonymous().trustLevel()).isEqualTo("untrusted");
	}
}
