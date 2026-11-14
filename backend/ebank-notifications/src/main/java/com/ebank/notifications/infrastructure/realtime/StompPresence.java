package com.ebank.notifications.infrastructure.realtime;

import com.ebank.notifications.application.Presence;
import com.ebank.realtime.api.RealtimeMessaging;
import org.springframework.stereotype.Component;

/** Online means subscribed to the notification queue, not just connected. */
@Component
class StompPresence implements Presence {

    private final RealtimeMessaging realtime;

    StompPresence(RealtimeMessaging realtime) {
        this.realtime = realtime;
    }

    @Override
    public boolean isOnline(String userId) {
        return realtime.isSubscribed(userId, NotificationsQueue.QUEUE);
    }
}
