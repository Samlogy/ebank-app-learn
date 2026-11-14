package com.ebank.notifications.infrastructure.messaging;

import com.ebank.chatbot.api.AdvisorMessageSent;
import com.ebank.common.outbox.OutboxEventHandler;
import com.ebank.notifications.application.NotificationRecorder;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AdvisorMessageSentHandler implements OutboxEventHandler<AdvisorMessageSent> {

    private final NotificationRecorder recorder;

    AdvisorMessageSentHandler(NotificationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String eventType() {
        return AdvisorMessageSent.TYPE;
    }

    @Override
    public Class<AdvisorMessageSent> payloadType() {
        return AdvisorMessageSent.class;
    }

    @Override
    public void handle(UUID eventId, AdvisorMessageSent payload) {
        recorder.onAdvisorMessage(eventId, payload);
    }
}
