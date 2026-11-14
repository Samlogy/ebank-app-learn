package com.ebank.realtime.stomp;

import com.ebank.realtime.api.RealtimeMessaging;
import com.ebank.realtime.api.UserQueue;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
class StompRealtimeMessaging implements RealtimeMessaging {

    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry users;

    StompRealtimeMessaging(SimpMessagingTemplate messaging, SimpUserRegistry users) {
        this.messaging = messaging;
        this.users = users;
    }

    @Override
    public void send(String userId, UserQueue queue, Object payload) {
        messaging.convertAndSendToUser(userId, queue.destination(), payload);
    }

    @Override
    public boolean isSubscribed(String userId, UserQueue queue) {
        SimpUser user = users.getUser(userId);
        return user != null && subscribed(user, queue);
    }

    @Override
    public Set<String> subscribedUsersWithRole(UserQueue queue, String role) {
        String authority = "ROLE_" + role;
        return users.getUsers().stream()
                .filter(user -> subscribed(user, queue))
                .filter(user -> user.getPrincipal() instanceof Authentication auth
                        && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority())))
                .map(SimpUser::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean subscribed(SimpUser user, UserQueue queue) {
        for (SimpSession session : user.getSessions()) {
            if (session.getSubscriptions().stream().anyMatch(s -> queue.subscription().equals(s.getDestination()))) {
                return true;
            }
        }
        return false;
    }
}
