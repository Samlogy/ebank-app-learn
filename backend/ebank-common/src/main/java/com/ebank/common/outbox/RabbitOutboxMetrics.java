package com.ebank.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code ebank_outbox_dead_letters{handler}}: messages a handler gave up on, waiting in its
 * dead-letter queue. Read from the broker on a timer, not on every scrape.
 */
@Component
@ConditionalOnBooleanProperty("ebank.outbox.rabbitmq.enabled")
class RabbitOutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(RabbitOutboxMetrics.class);

    private final AmqpAdmin admin;
    private final Map<String, AtomicLong> deadLetters = new ConcurrentHashMap<>();

    RabbitOutboxMetrics(AmqpAdmin admin, MeterRegistry meters, List<OutboxEventHandler<?>> handlers) {
        this.admin = admin;
        for (OutboxEventHandler<?> handler : handlers) {
            AtomicLong count = new AtomicLong();
            deadLetters.put(RabbitOutboxConfig.deadLetterQueueOf(handler), count);
            String name = handler.handlerName();
            Gauge.builder("ebank.outbox.dead.letters", count, AtomicLong::get)
                    .tag("handler", name.substring(name.lastIndexOf('.') + 1))
                    .description("Outbox messages in the handler's dead-letter queue")
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${ebank.outbox.metrics-interval-ms:30000}", initialDelay = 5000)
    void refresh() {
        deadLetters.forEach((queue, count) -> {
            try {
                Properties info = admin.getQueueProperties(queue);
                if (info != null) {
                    count.set(((Number) info.getOrDefault(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0)).longValue());
                }
            } catch (RuntimeException ex) {
                log.debug("Dead-letter count of {} not refreshed: {}", queue, ex.getClass().getSimpleName());
            }
        });
    }
}
