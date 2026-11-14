package com.ebank.notifications.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ebank.auth.api.TokenAuthenticator;
import com.ebank.auth.api.TokenAuthenticator.AuthenticatedToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class StompSecurityInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Mock TokenAuthenticator tokens;
    private StompSecurityInterceptor interceptor;
    private Map<String, Object> session;

    @BeforeEach
    void setUp() {
        interceptor = new StompSecurityInterceptor(tokens, Clock.fixed(NOW, ZoneOffset.UTC));
        session = new HashMap<>();
    }

    private Message<?> frame(StompCommand command, String destination, String authorization, String user) {
        StompHeaderAccessor stomp = StompHeaderAccessor.create(command);
        stomp.setSessionId("s1");
        stomp.setSessionAttributes(session);
        stomp.setLeaveMutable(true);
        if (destination != null) {
            stomp.setDestination(destination);
        }
        if (authorization != null) {
            stomp.setNativeHeader("Authorization", authorization);
        }
        if (user != null) {
            stomp.setUser(new TestingAuthenticationToken(user, null));
        }
        return MessageBuilder.createMessage(new byte[0], stomp.getMessageHeaders());
    }

    private static AuthenticatedToken token(String user, Instant expiresAt) {
        return new AuthenticatedToken(new TestingAuthenticationToken(user, null, List.of()), expiresAt);
    }

    @Test
    void connectWithAValidTokenSetsTheUserAndExpiry() {
        when(tokens.authenticate("good")).thenReturn(token("alice", NOW.plusSeconds(300)));

        Message<?> result = interceptor.preSend(frame(StompCommand.CONNECT, null, "Bearer good", null), null);

        assertThat(StompHeaderAccessor.wrap(result).getUser().getName()).isEqualTo("alice");
        assertThat(session.get(StompSecurityInterceptor.EXPIRES_AT)).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void connectWithoutAValidTokenIsRejected() {
        when(tokens.authenticate(null)).thenThrow(new BadCredentialsException("no"));
        when(tokens.authenticate("bad")).thenThrow(new BadCredentialsException("no"));

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, null, null), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, "Bearer bad", null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void subscribingToTheOwnNotificationQueueIsAllowed() {
        session.put(StompSecurityInterceptor.EXPIRES_AT, NOW.plusSeconds(60));

        Message<?> message = frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", null, "alice");

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    @Test
    void otherSubscriptionsAreRejected() {
        session.put(StompSecurityInterceptor.EXPIRES_AT, NOW.plusSeconds(60));

        for (String destination : List.of("/queue/notifications", "/queue/notifications-users1", "/user/queue/other", "/topic/all")) {
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, null, "alice"), null))
                    .as(destination)
                    .isInstanceOf(MessageDeliveryException.class);
        }
    }

    @Test
    void framesAfterTheTokenExpiredAreRejected() {
        session.put(StompSecurityInterceptor.EXPIRES_AT, NOW);

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", null, "alice"), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Token expired");
    }

    @Test
    void framesBeforeConnectAreRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", null, null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void sendingAnywhereButTheRefreshDestinationIsRejected() {
        session.put(StompSecurityInterceptor.EXPIRES_AT, NOW.plusSeconds(60));

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/app/anything", null, "alice"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void refreshExtendsTheConnectionForTheSameUserOnly() {
        session.put(StompSecurityInterceptor.EXPIRES_AT, NOW.plusSeconds(10));
        when(tokens.authenticate("fresh")).thenReturn(token("alice", NOW.plusSeconds(300)));
        when(tokens.authenticate("mallory")).thenReturn(token("mallory", NOW.plusSeconds(300)));

        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/token", "Bearer fresh", "alice"), null)).isNull();
        assertThat(session.get(StompSecurityInterceptor.EXPIRES_AT)).isEqualTo(NOW.plusSeconds(300));

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/app/token", "Bearer mallory", "alice"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }
}
