package dev.arcp.core.lease;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * §9.5 lease_constraints. {@code expires_at} MUST be UTC ({@code Z} suffix) and MUST be in the
 * future at submit time. Past or non-{@code Z} values are rejected as {@code INVALID_REQUEST}.
 *
 * @param expiresAt UTC instant at which the lease expires, or {@code null} for no expiration
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LeaseConstraints(@Nullable Instant expiresAt) {

  /**
   * Returns constraints with no expiration.
   *
   * @return the unconstrained instance
   */
  public static LeaseConstraints none() {
    return new LeaseConstraints(null);
  }

  /**
   * Creates constraints expiring at the given instant.
   *
   * @param expiresAt the expiry instant
   * @return the constraints
   */
  public static LeaseConstraints of(Instant expiresAt) {
    return new LeaseConstraints(expiresAt);
  }

  /**
   * Serializes the expiry under its wire name {@code expires_at}.
   *
   * @return the expiry instant, or {@code null}
   */
  @JsonProperty("expires_at")
  public @Nullable Instant expiresAtJson() {
    return expiresAt;
  }

  @JsonCreator
  static LeaseConstraints fromJson(@JsonProperty("expires_at") @Nullable String raw) {
    if (raw == null) {
      return none();
    }
    return new LeaseConstraints(parseStrictUtc(raw));
  }

  /**
   * Parser: only ISO-8601 with {@code Z} suffix accepted; offsets or naive datetimes rejected.
   *
   * @param raw the wire {@code expires_at} string
   * @return the parsed instant
   * @throws IllegalArgumentException if {@code raw} is not ISO-8601 UTC with a {@code Z} suffix
   */
  public static Instant parseStrictUtc(String raw) {
    try {
      ZonedDateTime zdt = ZonedDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME);
      if (!zdt.getZone().equals(ZoneOffset.UTC)) {
        throw new IllegalArgumentException("expires_at must be UTC (Z suffix): " + raw);
      }
      if (!raw.endsWith("Z")) {
        throw new IllegalArgumentException("expires_at must use Z suffix: " + raw);
      }
      return zdt.toInstant();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("invalid expires_at: " + raw, e);
    }
  }
}
