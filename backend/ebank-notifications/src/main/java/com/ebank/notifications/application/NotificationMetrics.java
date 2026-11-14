package com.ebank.notifications.application;

import com.ebank.notifications.domain.NotificationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code ebank_notifications_created_total{type}} and
 * {@code ebank_notifications_delivered_total{channel, outcome}}.
 */
@Component
class NotificationMetrics {

    private final MeterRegistry registry;

    NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void created(NotificationType type) {
        Counter.builder("ebank.notifications.created")
                .description("Notifications recorded")
                .tag("type", type.name())
                .register(registry)
                .increment();
    }

    /**
     * @param channel live, email or sms
     * @param outcome sent, skipped or failed
     */
    void delivery(String channel, String outcome) {
        Counter.builder("ebank.notifications.delivered")
                .description("Notification delivery attempts by channel")
                .tag("channel", channel)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
