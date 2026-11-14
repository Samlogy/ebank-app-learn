package com.ebank.notifications.infrastructure.realtime;

import com.ebank.notifications.application.LivePublisher;
import com.ebank.notifications.application.NotificationView;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
class StompLivePublisher implements LivePublisher {

    private final SimpMessagingTemplate messaging;

    StompLivePublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @Override
    public void push(String userId, NotificationView notification) {
        messaging.convertAndSendToUser(userId, WebSocketConfig.NOTIFICATIONS_DESTINATION, notification);
    }
}
