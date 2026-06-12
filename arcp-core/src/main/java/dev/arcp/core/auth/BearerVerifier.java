package dev.arcp.core.auth;

import dev.arcp.core.error.UnauthenticatedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SPI for verifying §6.1 bearer tokens at the handshake seam. */
@FunctionalInterface
public interface BearerVerifier {
  /**
   * Verifies a bearer token presented in {@code session.hello.payload.auth.token}.
   *
   * @param token the presented bearer token, possibly {@code null}
   * @return the authenticated principal
   * @throws UnauthenticatedException if the token is missing or invalid
   */
  Principal verify(String token) throws UnauthenticatedException;

  /**
   * Static-token verifier that compares the supplied bearer token to {@code expected} in
   * constant-time using {@link MessageDigest#isEqual(byte[], byte[])}. Suitable for production use
   * with static credentials.
   *
   * @param expected the token value to require
   * @param principal the principal returned on a successful match
   * @return a verifier accepting exactly {@code expected}
   */
  static BearerVerifier staticToken(String expected, Principal principal) {
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    return token -> {
      if (token == null) {
        throw new UnauthenticatedException("invalid bearer token");
      }
      byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
      if (tokenBytes.length != expectedBytes.length
          || !MessageDigest.isEqual(expectedBytes, tokenBytes)) {
        throw new UnauthenticatedException("invalid bearer token");
      }
      return principal;
    };
  }

  /**
   * Accepts any non-empty token, returning a principal derived from a SHA-256 digest of the token
   * bytes (first 16 bytes hex-encoded). Avoids the principal-collision risk of using {@code
   * String#hashCode}.
   *
   * @return a verifier accepting any non-empty token
   */
  static BearerVerifier acceptAny() {
    return token -> {
      if (token == null || token.isEmpty()) {
        throw new UnauthenticatedException("empty bearer token");
      }
      try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
        byte[] head = new byte[16];
        System.arraycopy(digest, 0, head, 0, 16);
        return new Principal("bearer:" + HexFormat.of().formatHex(head));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable", e);
      }
    };
  }
}
