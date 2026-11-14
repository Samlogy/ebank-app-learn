package com.ebank.notifications.infrastructure.realtime;

import com.ebank.auth.api.TokenAuthenticator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * One STOMP connection per user at {@code /ws}. It carries notifications now, and chat and call
 * signaling later, so this configuration moves to a shared module when the chatbot needs it.
 *
 * <p>The simple in-memory broker only works with one instance. Several instances need a broker
 * relay (RabbitMQ STOMP) and sticky sessions or a shared user registry.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String NOTIFICATIONS_DESTINATION = "/queue/notifications";
    private static final int MESSAGE_SIZE_LIMIT = 16 * 1024;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    private final StompSecurityInterceptor security;
    private final ExpiredSessionCloser expiredSessions;
    private final List<String> allowedOrigins;

    WebSocketConfig(
            TokenAuthenticator tokens,
            ExpiredSessionCloser expiredSessions,
            @Value("${ebank.security.cors-allowed-origins:}") List<String> allowedOrigins) {
        this.security = new StompSecurityInterceptor(tokens, Clock.systemUTC());
        this.expiredSessions = expiredSessions;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The same origins as CORS: the handshake is refused from any other site.
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(security);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(MESSAGE_SIZE_LIMIT)
                .setSendBufferSizeLimit(SEND_BUFFER_LIMIT)
                .setSendTimeLimit(SEND_TIME_LIMIT_MS)
                .addDecoratorFactory(expiredSessions::decorate);
    }

    @Configuration(proxyBeanMethods = false)
    static class Sessions {

        @Bean
        ExpiredSessionCloser expiredSessionCloser(MeterRegistry meters) {
            ExpiredSessionCloser closer = new ExpiredSessionCloser(Clock.systemUTC());
            Gauge.builder("ebank.websocket.sessions", closer, ExpiredSessionCloser::openSessions)
                    .description("Open WebSocket connections on this instance")
                    .register(meters);
            return closer;
        }
    }
}
