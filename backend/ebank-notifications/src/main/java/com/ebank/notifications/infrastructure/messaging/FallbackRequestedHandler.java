package com.ebank.notifications.infrastructure.messaging;

import com.ebank.common.outbox.OutboxEventHandler;
import com.ebank.notifications.application.FallbackDelivery;
import com.ebank.notifications.application.FallbackRequested;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class FallbackRequestedHandler implements OutboxEventHandler<FallbackRequested> {

    private final FallbackDelivery delivery;

    FallbackRequestedHandler(FallbackDelivery delivery) {
        this.delivery = delivery;
    }

    @Override
    public String eventType() {
        return FallbackRequested.TYPE;
    }

    @Override
    public Class<FallbackRequested> payloadType() {
        return FallbackRequested.class;
    }

    @Override
    public void handle(UUID eventId, FallbackRequested payload) {
        delivery.deliver(payload.notificationId());
    }
}
