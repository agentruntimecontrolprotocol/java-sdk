package dev.arcp.middleware.spring;

import dev.arcp.runtime.ArcpRuntime;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring Boot auto-configuration that registers an {@link ArcpWebSocketHandler} at the configured
 * path whenever an {@link ArcpRuntime} bean is present.
 */
@AutoConfiguration
@ConditionalOnClass({EnableWebSocket.class, WebSocketConfigurer.class})
@ConditionalOnBean(ArcpRuntime.class)
@EnableConfigurationProperties(ArcpSpringBootProperties.class)
@EnableWebSocket
public class ArcpSpringBootAutoConfiguration implements WebSocketConfigurer {

  private final ArcpRuntime runtime;
  private final ArcpSpringBootProperties properties;

  /**
   * Creates the auto-configuration; invoked by Spring with the discovered beans.
   *
   * @param runtime the {@link ArcpRuntime} bean that accepted sessions are handed to
   * @param properties the {@code arcp.middleware.*} configuration properties
   */
  public ArcpSpringBootAutoConfiguration(ArcpRuntime runtime, ArcpSpringBootProperties properties) {
    this.runtime = runtime;
    this.properties = properties;
  }

  /**
   * Exposes the handler that serves ARCP JSON envelopes as WebSocket text frames per §4.
   *
   * @return the handler registered at the {@code arcp.middleware.path} endpoint
   */
  @Bean
  public ArcpWebSocketHandler arcpWebSocketHandler() {
    return new ArcpWebSocketHandler(runtime);
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    String[] origins =
        properties.getAllowedOrigins().isEmpty()
            ? new String[] {"*"}
            : properties.getAllowedOrigins().toArray(new String[0]);
    var registration =
        registry
            .addHandler(arcpWebSocketHandler(), properties.getPath())
            .setAllowedOrigins(origins);
    // §14: enforce the Host allowlist before the upgrade completes so a disallowed Host is rejected
    // (403) rather than being a silently ignored security control (#99).
    List<String> allowedHosts = properties.getAllowedHosts();
    if (!allowedHosts.isEmpty()) {
      registration.addInterceptors(new HostAllowlistHandshakeInterceptor(allowedHosts));
    }
  }
}
