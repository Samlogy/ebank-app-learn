package com.ebank.notifications.infrastructure.realtime;

import com.ebank.realtime.api.UserQueue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Clients subscribe to {@code /user/queue/notifications}. */
@Configuration(proxyBeanMethods = false)
class NotificationsQueue {

    static final UserQueue QUEUE = new UserQueue("/queue/notifications");

    @Bean
    UserQueue notificationsUserQueue() {
        return QUEUE;
    }
}
