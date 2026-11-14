package com.ebank.notifications.infrastructure.messaging;

import com.ebank.common.outbox.OutboxEventHandler;
import com.ebank.notifications.application.NotificationRecorder;
import com.ebank.transactions.api.TransactionCancelled;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class TransactionCancelledHandler implements OutboxEventHandler<TransactionCancelled> {

    private final NotificationRecorder recorder;

    TransactionCancelledHandler(NotificationRecorder recorder) {
        this.recorder = recorder;
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
        recorder.onTransactionCancelled(eventId, payload);
    }
}
