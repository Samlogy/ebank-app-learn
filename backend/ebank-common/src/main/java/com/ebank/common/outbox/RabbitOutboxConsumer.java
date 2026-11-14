package com.ebank.common.outbox;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

/**
 * Delivers the messages of one handler's queue to that handler. Delivery is at least once:
 * {@link OutboxStore#handle} records each (event, handler) pair in the same transaction as the
 * handler's writes, so a redelivered message is acknowledged without running the handler again.
 * A failure is retried by the container, then the message goes to the dead-letter queue.
 */
class RabbitOutboxConsumer implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitOutboxConsumer.class);

    private final OutboxStore store;
    private final OutboxEventHandler<?> handler;

    RabbitOutboxConsumer(OutboxStore store, OutboxEventHandler<?> handler) {
        this.store = store;
        this.handler = handler;
    }

    @Override
    public void onMessage(Message message) {
        UUID eventId;
        try {
            eventId = UUID.fromString(message.getMessageProperties().getMessageId());
        } catch (RuntimeException ex) {
            // Not from the outbox: no retry can fix it.
            throw new AmqpRejectAndDontRequeueException("Message without an outbox event ID", ex);
        }
        if (!store.handle(eventId, new String(message.getBody(), StandardCharsets.UTF_8), handler)) {
            log.debug("Outbox event {} already handled by {}: redelivery ignored", eventId, handler.handlerName());
        }
    }
}
