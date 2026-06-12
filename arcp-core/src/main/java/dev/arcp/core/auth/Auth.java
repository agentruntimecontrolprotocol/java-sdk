package dev.arcp.core.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * §6.1 authentication block. v1 supports {@code bearer} and {@code anonymous}.
 *
 * @param scheme the authentication scheme, {@link #BEARER} or {@link #ANONYMOUS}
 * @param token the bearer token, or {@code null} for {@code anonymous}
 */
public record Auth(String scheme, @Nullable String token) {

  /** Wire value of the bearer-token scheme ({@code "bearer"}). */
  public static final String BEARER = "bearer";

  /** Wire value of the unauthenticated scheme ({@code "anonymous"}). */
  public static final String ANONYMOUS = "anonymous";

  /** Canonical constructor requiring a non-null scheme. */
  @JsonCreator
  public Auth(
      @JsonProperty("scheme") String scheme, @JsonProperty("token") @Nullable String token) {
    this.scheme = Objects.requireNonNull(scheme, "scheme");
    this.token = token;
  }

  /**
   * Creates a bearer-token auth block carried in {@code session.hello.payload.auth} (§6.1).
   *
   * @param token the bearer token
   * @return an auth block with scheme {@code bearer}
   */
  public static Auth bearer(String token) {
    return new Auth(BEARER, Objects.requireNonNull(token, "token"));
  }

  /**
   * Creates an anonymous auth block carrying no credential material.
   *
   * @return an auth block with scheme {@code anonymous}
   */
  public static Auth anonymous() {
    return new Auth(ANONYMOUS, null);
  }
}
