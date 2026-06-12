package dev.arcp.core;

/** Protocol and SDK version constants. */
public final class Version {
  /** ARCP protocol version implemented by this SDK, carried in the envelope {@code arcp} field. */
  public static final String PROTOCOL = "1.1";

  /** Version of this ARCP Java SDK distribution. */
  public static final String SDK = "1.0.0";

  private Version() {}
}
