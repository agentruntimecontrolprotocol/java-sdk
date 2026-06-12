package dev.arcp.middleware.spring;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound at {@code arcp.middleware.*} in {@code application.yml}. */
@ConfigurationProperties("arcp.middleware")
public final class ArcpSpringBootProperties {

  private String path = "/arcp";
  private List<String> allowedHosts = List.of();
  private List<String> allowedOrigins = List.of();

  /** Creates the property holder with defaults: {@code /arcp} path and empty allowlists. */
  public ArcpSpringBootProperties() {}

  /**
   * Returns the request path the WebSocket handler is mounted at.
   *
   * @return the endpoint path, {@code /arcp} by default
   */
  public String getPath() {
    return path;
  }

  /**
   * Sets the request path the WebSocket handler is mounted at ({@code arcp.middleware.path}).
   *
   * @param path the endpoint path
   */
  public void setPath(String path) {
    this.path = path;
  }

  /**
   * Returns the {@code Host} header allowlist enforced before the handshake (§14).
   *
   * @return the allowed {@code Host} values; empty (the default) disables the check
   */
  public List<String> getAllowedHosts() {
    return allowedHosts;
  }

  /**
   * Sets the {@code Host} header allowlist ({@code arcp.middleware.allowed-hosts}); upgrades from
   * any other host are rejected with HTTP 403 per §14.
   *
   * @param allowedHosts the allowed {@code Host} header values
   */
  public void setAllowedHosts(List<String> allowedHosts) {
    this.allowedHosts = List.copyOf(allowedHosts);
  }

  /**
   * Returns the {@code Origin} values accepted during the WebSocket handshake.
   *
   * @return the allowed {@code Origin} values; empty (the default) allows all origins
   */
  public List<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  /**
   * Sets the {@code Origin} allowlist ({@code arcp.middleware.allowed-origins}) applied to the
   * WebSocket handler registration.
   *
   * @param allowedOrigins the allowed {@code Origin} header values
   */
  public void setAllowedOrigins(List<String> allowedOrigins) {
    this.allowedOrigins = List.copyOf(allowedOrigins);
  }
}
