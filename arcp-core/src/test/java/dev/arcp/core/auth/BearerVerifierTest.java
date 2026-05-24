package dev.arcp.core.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.arcp.core.error.UnauthenticatedException;
import org.junit.jupiter.api.Test;

class BearerVerifierTest {

  @Test
  void staticToken_acceptsMatching() {
    BearerVerifier v = BearerVerifier.staticToken("sekret", new Principal("p"));
    assertDoesNotThrow(() -> v.verify("sekret"));
  }

  @Test
  void staticToken_rejectsNullAndMismatch() {
    BearerVerifier v = BearerVerifier.staticToken("sekret", new Principal("p"));
    assertThrows(UnauthenticatedException.class, () -> v.verify(null));
    assertThrows(UnauthenticatedException.class, () -> v.verify("wrong"));
    assertThrows(UnauthenticatedException.class, () -> v.verify("sekrett"));
  }

  @Test
  void acceptAny_distinguishesHashCodeCollidingTokens() throws Exception {
    // "Aa" and "BB" share the same String.hashCode() (2112). With a hashCode-derived
    // principal id, both would authenticate as the same identity. The fix uses SHA-256.
    BearerVerifier v = BearerVerifier.acceptAny();
    Principal a = v.verify("Aa");
    Principal b = v.verify("BB");
    assertNotEquals(a.id(), b.id());
  }
}
