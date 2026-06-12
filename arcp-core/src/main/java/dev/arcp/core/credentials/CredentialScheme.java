package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * Authentication scheme of a §9.8.1 provisioned credential. {@code bearer} is the only scheme
 * defined by the ARCP v1.1 spec.
 */
public enum CredentialScheme {
  /**
   * Bearer-token credential ({@code bearer}): {@code value} is presented as a bearer secret at the
   * credential's {@code endpoint}.
   */
  BEARER("bearer"),

  /**
   * §9.8.1: implementations MAY define extension schemes (e.g. {@code basic}, {@code signed_url});
   * unknown schemes MUST be ignored by clients that do not recognize them. Decoding an unrecognized
   * scheme yields {@code UNKNOWN} rather than throwing, so a single extension credential cannot
   * fail decode of an entire {@code job.accepted} (see #97). Consumers filter credentials whose
   * scheme is {@code UNKNOWN}.
   */
  UNKNOWN("unknown");

  private final String wire;

  CredentialScheme(String wire) {
    this.wire = wire;
  }

  /**
   * Returns the canonical wire string for this scheme.
   *
   * @return the wire string
   */
  @JsonValue
  public String wire() {
    return wire;
  }

  /**
   * Returns whether this is the {@code bearer} scheme.
   *
   * @return {@code true} for {@link #BEARER}
   */
  public boolean isBearer() {
    return this == BEARER;
  }

  /**
   * Resolves a wire string to a scheme, yielding {@link #UNKNOWN} for unrecognized extension
   * schemes per §9.8.1 rather than failing decode.
   *
   * @param wire the wire scheme string
   * @return the matching scheme, or {@link #UNKNOWN}
   */
  @JsonCreator
  public static CredentialScheme fromWire(String wire) {
    return Arrays.stream(values())
        .filter(scheme -> scheme.wire.equals(wire))
        .findFirst()
        .orElse(UNKNOWN);
  }
}
