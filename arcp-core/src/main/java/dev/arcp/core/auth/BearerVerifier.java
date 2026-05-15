package dev.arcp.core.auth;

import dev.arcp.core.error.UnauthenticatedException;

/** SPI for verifying §6.1 bearer tokens at the handshake seam. */
@FunctionalInterface
public interface BearerVerifier {
    Principal verify(String token) throws UnauthenticatedException;

    /** Static-token verifier; suitable for development and tests. */
    static BearerVerifier staticToken(String expected, Principal principal) {
        return token -> {
            if (!expected.equals(token)) {
                throw new UnauthenticatedException("invalid bearer token");
            }
            return principal;
        };
    }

    /** Accept any non-empty token, returning a principal derived from its hash. */
    static BearerVerifier acceptAny() {
        return token -> {
            if (token == null || token.isEmpty()) {
                throw new UnauthenticatedException("empty bearer token");
            }
            return new Principal("bearer:" + Integer.toHexString(token.hashCode()));
        };
    }
}
