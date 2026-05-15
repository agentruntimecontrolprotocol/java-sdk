package dev.arcp.middleware.spring;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound at {@code arcp.middleware.*} in {@code application.yml}.
 */
@ConfigurationProperties("arcp.middleware")
public final class ArcpSpringBootProperties {

    private String path = "/arcp";
    private List<String> allowedHosts = List.of();
    private List<String> allowedOrigins = List.of();

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = List.copyOf(allowedHosts);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }
}
