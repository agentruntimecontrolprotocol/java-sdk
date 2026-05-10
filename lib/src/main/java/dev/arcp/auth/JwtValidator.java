package dev.arcp.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * HMAC-SHA256 JWT validator (RFC §8.2 {@code signed_jwt}). Verifies signature,
 * expiry, and issuer; returns a {@link Principal} keyed on the {@code sub}
 * claim.
 *
 * <p>
 * Production deployments substitute a richer validator for RSA/EC keys and
 * JWKS-backed key rotation; this implementation covers the v0.1 reference
 * surface.
 */
public final class JwtValidator implements CredentialValidator {

	private final JWSVerifier verifier;
	private final String expectedIssuer;
	private final Clock clock;

	public JwtValidator(byte[] hmacKey, String expectedIssuer) {
		this(hmacKey, expectedIssuer, Clock.systemUTC());
	}

	public JwtValidator(byte[] hmacKey, String expectedIssuer, Clock clock) {
		Objects.requireNonNull(hmacKey, "hmacKey");
		this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer");
		this.clock = Objects.requireNonNull(clock, "clock");
		try {
			this.verifier = new MACVerifier(hmacKey);
		} catch (JOSEException e) {
			throw new ARCPException(ErrorCode.INTERNAL, "could not build JWT verifier", e);
		}
	}

	@Override
	public Principal validate(Credentials credentials) {
		if (!(credentials instanceof Credentials.JwtCredentials jwt)) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "signed_jwt scheme required");
		}
		SignedJWT parsed;
		try {
			parsed = SignedJWT.parse(jwt.jwt());
		} catch (ParseException e) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "malformed JWT", e);
		}
		if (!JWSAlgorithm.HS256.equals(parsed.getHeader().getAlgorithm())) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "expected HS256 JWT");
		}
		try {
			if (!parsed.verify(verifier)) {
				throw new ARCPException(ErrorCode.UNAUTHENTICATED, "bad signature");
			}
		} catch (JOSEException e) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "verifier error", e);
		}
		JWTClaimsSet claims;
		try {
			claims = parsed.getJWTClaimsSet();
		} catch (ParseException e) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "malformed claims", e);
		}
		if (!expectedIssuer.equals(claims.getIssuer())) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "bad issuer");
		}
		if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(Instant.now(clock))) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "expired or missing exp");
		}
		String subject = claims.getSubject();
		if (subject == null || subject.isBlank()) {
			throw new ARCPException(ErrorCode.UNAUTHENTICATED, "missing subject");
		}
		String trust = claims.getClaim("trust_level") instanceof String s ? s : "trusted";
		return new Principal(subject, trust);
	}
}
