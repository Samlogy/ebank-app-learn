package com.ebank.notifications.infrastructure.realtime;

import com.ebank.notifications.application.LivePublisher;
import com.ebank.notifications.application.NotificationView;
import com.ebank.realtime.api.RealtimeMessaging;
import org.springframework.stereotype.Component;

@Component
class StompLivePublisher implements LivePublisher {

    private final RealtimeMessaging realtime;

    StompLivePublisher(RealtimeMessaging realtime) {
        this.realtime = realtime;
    }

    @Override
    public void push(String userId, NotificationView notification) {
        realtime.send(userId, NotificationsQueue.QUEUE, notification);
    }
}
