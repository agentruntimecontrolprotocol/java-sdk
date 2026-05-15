package dev.arcp.middleware.spring;

import dev.arcp.runtime.ArcpRuntime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring Boot auto-configuration that registers an {@link ArcpWebSocketHandler}
 * at the configured path whenever an {@link ArcpRuntime} bean is present.
 */
@AutoConfiguration
@ConditionalOnClass({EnableWebSocket.class, WebSocketConfigurer.class})
@ConditionalOnBean(ArcpRuntime.class)
@EnableConfigurationProperties(ArcpSpringBootProperties.class)
@EnableWebSocket
public class ArcpSpringBootAutoConfiguration implements WebSocketConfigurer {

    private final ArcpRuntime runtime;
    private final ArcpSpringBootProperties properties;

    public ArcpSpringBootAutoConfiguration(
            ArcpRuntime runtime, ArcpSpringBootProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    @Bean
    public ArcpWebSocketHandler arcpWebSocketHandler() {
        return new ArcpWebSocketHandler(runtime);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = properties.getAllowedOrigins().isEmpty()
                ? new String[] {"*"}
                : properties.getAllowedOrigins().toArray(new String[0]);
        registry.addHandler(arcpWebSocketHandler(), properties.getPath())
                .setAllowedOrigins(origins);
    }
}
