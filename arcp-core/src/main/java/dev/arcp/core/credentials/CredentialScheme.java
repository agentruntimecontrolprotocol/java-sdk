package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum CredentialScheme {
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

  @JsonValue
  public String wire() {
    return wire;
  }

  public boolean isBearer() {
    return this == BEARER;
  }

  @JsonCreator
  public static CredentialScheme fromWire(String wire) {
    return Arrays.stream(values())
        .filter(scheme -> scheme.wire.equals(wire))
        .findFirst()
        .orElse(UNKNOWN);
  }
}
