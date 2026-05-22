package dev.arcp.core.credentials;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum CredentialScheme {
  BEARER("bearer");

  private final String wire;

  CredentialScheme(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }

  @JsonCreator
  public static CredentialScheme fromWire(String wire) {
    return Arrays.stream(values())
        .filter(scheme -> scheme.wire.equals(wire))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown credential scheme: " + wire));
  }
}
