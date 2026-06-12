package dev.arcp.core.auth;

import java.util.Objects;

/**
 * Authenticated identity established by §6.1 verification. Job visibility (§6.6) and subscription
 * authorization (§7.6) are scoped per principal.
 *
 * @param id opaque stable identifier of the authenticated caller
 */
public record Principal(String id) {
  /** Canonical constructor requiring a non-null id. */
  public Principal {
    Objects.requireNonNull(id, "id");
  }
}
