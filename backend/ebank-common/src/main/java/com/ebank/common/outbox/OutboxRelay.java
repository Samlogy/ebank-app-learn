package com.ebank.common.outbox;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The in-process transport (the default): polls the outbox and hands each pending event to the
 * handlers of its type.
 *
 * <ul>
 *   <li>An event is claimed with a short <em>lease</em> ({@code FOR UPDATE SKIP LOCKED}, then
 *       {@code next_attempt_at} pushed forward), so several instances can run the relay and no
 *       database lock is held while handlers run. If an instance dies, the lease runs out and the
 *       event is picked up again.
 *   <li>Each handler runs in its own transaction, together with the row in
 *       {@code outbox_event_handled} that records its success. Handlers are independent: if
 *       analytics fails, notifications are still committed, and the retry only re-runs analytics.
 *   <li>The event becomes PUBLISHED once every handler has succeeded.
 * </ul>
 *
 * <p>With {@code ebank.outbox.rabbitmq.enabled=true}, {@link RabbitOutboxPublisher} publishes to
 * RabbitMQ instead and each handler consumes its own queue ({@link RabbitOutboxConsumer}).
 */
@Component
@ConditionalOnProperty(name = "ebank.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "ebank.outbox.rabbitmq.enabled", havingValue = "false", matchIfMissing = true)
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final OutboxProperties properties;
    private final Map<String, List<OutboxEventHandler<?>>> handlers;

    OutboxRelay(OutboxStore store, OutboxProperties properties, List<OutboxEventHandler<?>> handlers) {
        this.store = store;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.groupingBy(OutboxEventHandler::eventType));
    }

    @Scheduled(fixedDelayString = "${ebank.outbox.poll-interval-ms:1000}")
    void relayPending() {
        for (int i = 0; i < properties.batchSize(); i++) {
            if (!relayNext()) {
                return;
            }
        }
    }

    /** Returns false when there was nothing left to deliver, or the database is unavailable. */
    boolean relayNext() {
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

        RuntimeException failure = null;
        Set<String> done = store.alreadyHandled(event.id());
        for (OutboxEventHandler<?> handler : handlers.getOrDefault(event.type(), List.of())) {
            if (done.contains(handler.handlerName())) {
                continue;
            }
            try {
                store.handle(event.id(), event.payload(), handler);
            } catch (RuntimeException ex) {
                // The other handlers still run: one failing consumer must not hold back the rest.
                log.warn("Outbox handler {} failed on event {}: {}",
                        handler.handlerName(), event.id(), ex.getClass().getName());
                if (failure == null) {
                    failure = ex;
                }
            }
        }

        try {
            if (failure == null) {
                store.markPublished(event.id());
            } else {
                store.recordFailure(event, failure);
            }
        } catch (RuntimeException ex) {
            // The lease runs out and the event is retried. Handlers that succeeded are skipped.
            log.warn("Could not update outbox event {}: {}", event.id(), ex.getClass().getSimpleName());
        }
        return true;
    }
}
