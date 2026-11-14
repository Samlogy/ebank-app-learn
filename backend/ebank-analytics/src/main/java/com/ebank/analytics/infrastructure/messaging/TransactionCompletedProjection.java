package com.ebank.analytics.infrastructure.messaging;

import com.ebank.analytics.application.TransactionProjector;
import com.ebank.common.outbox.OutboxEventHandler;
import com.ebank.transactions.api.TransactionCompleted;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ebank.analytics.enabled", havingValue = "true", matchIfMissing = true)
class TransactionCompletedProjection implements OutboxEventHandler<TransactionCompleted> {

    private final TransactionProjector projector;

    TransactionCompletedProjection(TransactionProjector projector) {
        this.projector = projector;
    }

    @Override
    public String eventType() {
        return TransactionCompleted.TYPE;
    }

    @Override
    public Class<TransactionCompleted> payloadType() {
        return TransactionCompleted.class;
    }

    @Override
    public void handle(UUID eventId, TransactionCompleted payload) {
        projector.onCompleted(payload);
    }
}
