package com.ebank.notifications.infrastructure.realtime;

import com.ebank.auth.api.TokenAuthenticator;
import com.ebank.auth.api.TokenAuthenticator.AuthenticatedToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.AuthenticationException;

/**
 * Deny by default for STOMP frames coming from clients:
 *
 * <ul>
 *   <li>CONNECT needs a valid access token in its {@code Authorization} header.
 *   <li>Every later frame is refused once that token has expired. The client either reconnects
 *       or sends a fresh token to {@value #REFRESH_DESTINATION} before then.
 *   <li>SUBSCRIBE is only allowed to {@value #USER_NOTIFICATIONS}. Spring resolves {@code /user/...}
 *       destinations per session, so nobody can listen to another user's queue. Direct
 *       {@code /queue/...} subscriptions are refused, since those would bypass that resolution.
 *   <li>SEND is only allowed to {@value #REFRESH_DESTINATION} for now.
 * </ul>
 *
 * Throwing here makes Spring send an ERROR frame and close the connection.
 */
class StompSecurityInterceptor implements ChannelInterceptor {

    static final String REFRESH_DESTINATION = "/app/token";
    /** Key in the WebSocket session attributes, also read by {@link ExpiredSessionCloser}. */
    static final String EXPIRES_AT = "ebank.tokenExpiresAt";

    private static final Logger log = LoggerFactory.getLogger(StompSecurityInterceptor.class);
    private static final String BEARER = "Bearer ";
    static final String USER_NOTIFICATIONS = "/user" + WebSocketConfig.NOTIFICATIONS_DESTINATION;

    private final TokenAuthenticator tokens;
    private final Clock clock;

    StompSecurityInterceptor(TokenAuthenticator tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return message;    // heartbeats
        }
        StompCommand command = stomp.getCommand();
        switch (command) {
            case CONNECT, STOMP -> connect(stomp);
            case DISCONNECT -> {}
            case SUBSCRIBE -> {
                requireLiveToken(stomp);
                if (!USER_NOTIFICATIONS.equals(stomp.getDestination())) {
                    throw reject("Subscription not allowed");
                }
            }
            case SEND -> {
                requireLiveToken(stomp);
                if (REFRESH_DESTINATION.equals(stomp.getDestination())) {
                    refresh(stomp);
                    return null;    // handled here, nothing to route
                }
                throw reject("Destination not allowed");
            }
            case UNSUBSCRIBE, ACK, NACK, BEGIN, COMMIT, ABORT -> requireLiveToken(stomp);
            default -> throw reject("Frame not allowed");
        }
        return message;
    }

    private void connect(StompHeaderAccessor stomp) {
        AuthenticatedToken token = authenticate(stomp);
        stomp.setUser(token.authentication());
        attributes(stomp).put(EXPIRES_AT, token.expiresAt());
    }

    /** The new token must belong to the same user: a connection never changes hands. */
    private void refresh(StompHeaderAccessor stomp) {
        AuthenticatedToken token = authenticate(stomp);
        if (stomp.getUser() == null || !token.userId().equals(stomp.getUser().getName())) {
            throw reject("Token belongs to another user");
        }
        attributes(stomp).put(EXPIRES_AT, token.expiresAt());
    }

    private AuthenticatedToken authenticate(StompHeaderAccessor stomp) {
        String header = stomp.getFirstNativeHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? header.substring(BEARER.length()).trim()
                : null;
        try {
            return tokens.authenticate(token);
        } catch (AuthenticationException ex) {
            throw reject("Authentication failed");
        }
    }

    private void requireLiveToken(StompHeaderAccessor stomp) {
        if (stomp.getUser() == null || !(attributes(stomp).get(EXPIRES_AT) instanceof Instant expiresAt)) {
            throw reject("Not authenticated");
        }
        if (!clock.instant().isBefore(expiresAt)) {
            throw reject("Token expired");
        }
    }

    private static Map<String, Object> attributes(StompHeaderAccessor stomp) {
        Map<String, Object> attributes = stomp.getSessionAttributes();
        if (attributes == null) {
            throw reject("No session");
        }
        return attributes;
    }

    /** The reason goes to the client and the debug log only. It never includes the token. */
    private static MessageDeliveryException reject(String reason) {
        log.debug("STOMP frame rejected: {}", reason);
        return new MessageDeliveryException(reason);
    }
}
