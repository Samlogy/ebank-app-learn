package com.ebank.notifications.infrastructure.realtime;

import com.ebank.notifications.application.Presence;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

/**
 * A user is online while they have at least one STOMP session subscribed to their notification queue.
 * Only knows this instance's sessions: several instances need a broker relay and a shared registry.
 */
@Component
class StompPresence implements Presence {

    private final SimpUserRegistry users;

    StompPresence(SimpUserRegistry users) {
        this.users = users;
    }

    @Override
    public boolean isOnline(String userId) {
        SimpUser user = users.getUser(userId);
        return user != null && user.getSessions().stream()
                .anyMatch(session -> session.getSubscriptions().stream()
                        .anyMatch(subscription -> StompSecurityInterceptor.USER_NOTIFICATIONS.equals(subscription.getDestination())));
    }
}
