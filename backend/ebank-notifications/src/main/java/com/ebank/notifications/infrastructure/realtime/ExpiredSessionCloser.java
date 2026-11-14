package com.ebank.notifications.infrastructure.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * A connection that only receives never sends a frame that {@link StompSecurityInterceptor}
 * could refuse, so expired connections are also closed on a timer.
 */
class ExpiredSessionCloser {

    /** Custom close code (4000-4999 are for applications): the client should get a new token and reconnect. */
    static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4001, "Token expired");

    private static final Logger log = LoggerFactory.getLogger(ExpiredSessionCloser.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    ExpiredSessionCloser(Clock clock) {
        this.clock = clock;
    }

    WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    int openSessions() {
        return sessions.size();
    }

    @Scheduled(fixedDelayString = "${ebank.notifications.websocket.expiry-check-ms:15000}")
    void closeExpired() {
        Instant now = clock.instant();
        sessions.values().forEach(session -> {
            // Sessions that have not sent CONNECT yet are closed by Spring's time-to-first-message.
            if (session.getAttributes().get(StompSecurityInterceptor.EXPIRES_AT) instanceof Instant expiresAt
                    && !now.isBefore(expiresAt)) {
                try {
                    session.close(TOKEN_EXPIRED);
                } catch (IOException ex) {
                    log.debug("Closing expired WebSocket session failed: {}", ex.getClass().getSimpleName());
                }
            }
        });
    }
}
