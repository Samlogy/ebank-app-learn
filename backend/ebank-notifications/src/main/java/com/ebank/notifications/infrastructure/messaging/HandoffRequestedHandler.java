package com.ebank.notifications.infrastructure.messaging;

import com.ebank.chatbot.api.HandoffRequested;
import com.ebank.common.outbox.OutboxEventHandler;
import com.ebank.notifications.application.AdvisorAlerts;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class HandoffRequestedHandler implements OutboxEventHandler<HandoffRequested> {

    private final AdvisorAlerts alerts;

    HandoffRequestedHandler(AdvisorAlerts alerts) {
        this.alerts = alerts;
    }

    @Override
    public String eventType() {
        return HandoffRequested.TYPE;
    }

    @Override
    public Class<HandoffRequested> payloadType() {
        return HandoffRequested.class;
    }

    @Override
    public void handle(UUID eventId, HandoffRequested payload) {
        alerts.onHandoff(payload);
    }
}
