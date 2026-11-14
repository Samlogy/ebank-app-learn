package com.ebank.notifications.infrastructure.messaging;

import com.ebank.common.outbox.OutboxEventHandler;
import com.ebank.notifications.application.NotificationRecorder;
import com.ebank.transactions.api.TransactionCompleted;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class TransactionCompletedHandler implements OutboxEventHandler<TransactionCompleted> {

    private final NotificationRecorder recorder;

    TransactionCompletedHandler(NotificationRecorder recorder) {
        this.recorder = recorder;
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
        recorder.onTransactionCompleted(eventId, payload);
    }
}
