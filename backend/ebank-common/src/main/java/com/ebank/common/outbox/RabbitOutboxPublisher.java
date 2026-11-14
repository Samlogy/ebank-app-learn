package com.ebank.common.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes pending outbox events to RabbitMQ. An event becomes PUBLISHED only once the broker has
 * confirmed it (publisher confirms): it is then on disk in every queue bound to its type. Failures
 * keep the event PENDING with the same back-off as the in-process relay, and the claim lease lets
 * several instances publish without sending an event twice at the same time.
 */
@Component
@ConditionalOnBooleanProperty("ebank.outbox.rabbitmq.enabled")
@ConditionalOnProperty(name = "ebank.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
class RabbitOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitOutboxPublisher.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxStore store;
    private final OutboxProperties properties;
    private final RabbitTemplate rabbit;

    RabbitOutboxPublisher(OutboxStore store, OutboxProperties properties, RabbitTemplate rabbit) {
        this.store = store;
        this.properties = properties;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${ebank.outbox.poll-interval-ms:1000}")
    void publishPending() {
        for (int i = 0; i < properties.batchSize(); i++) {
            if (!publishNext()) {
                return;
            }
        }
    }

    /** Returns false when there was nothing left to publish, or the database is unavailable. */
    boolean publishNext() {
        OutboxStore.PendingEvent event;
        try {
            event = store.claimNext();
        } catch (RuntimeException ex) {
            log.warn("Outbox poll failed: {}", ex.getClass().getSimpleName());
            return false;
        }
        if (event == null) {
            return false;
        }
        try {
            send(event);
            store.markPublished(event.id());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                store.recordFailure(event, ex);
            } catch (RuntimeException dbFailure) {
                // The lease runs out and the event is published again: consumers skip duplicates.
                log.warn("Could not update outbox event {}: {}", event.id(), dbFailure.getClass().getSimpleName());
            }
            // The broker is probably down: stop this batch instead of failing every event in it.
            return false;
        }
        return true;
    }

    private void send(OutboxStore.PendingEvent event) throws InterruptedException, ExecutionException, TimeoutException {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.id().toString());
        props.setType(event.type());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        CorrelationData correlation = new CorrelationData(event.id().toString());
        rabbit.send(RabbitOutboxConfig.EVENTS, event.type(), new Message(event.payload().getBytes(StandardCharsets.UTF_8), props), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.ack()) {
            throw new AmqpException("Broker refused outbox event " + event.id() + ": " + confirm.reason());
        }
    }
}
