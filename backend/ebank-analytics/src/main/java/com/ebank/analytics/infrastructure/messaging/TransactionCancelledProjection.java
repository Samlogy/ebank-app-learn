package com.ebank.analytics.infrastructure.messaging;

import com.ebank.analytics.application.TransactionProjector;
import com.ebank.common.outbox.OutboxEventHandler;
import com.ebank.transactions.api.TransactionCancelled;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ebank.analytics.enabled", havingValue = "true", matchIfMissing = true)
class TransactionCancelledProjection implements OutboxEventHandler<TransactionCancelled> {

    private final TransactionProjector projector;

    TransactionCancelledProjection(TransactionProjector projector) {
        this.projector = projector;
    }

    @Override
    public String eventType() {
        return TransactionCancelled.TYPE;
    }

    @Override
    public Class<TransactionCancelled> payloadType() {
        return TransactionCancelled.class;
    }

    @Override
    public void handle(UUID eventId, TransactionCancelled payload) {
        projector.onCancelled(payload);
    }
}
