package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebank.common.outbox.Outbox;
import com.ebank.common.outbox.OutboxEventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The outbox over a real RabbitMQ: one queue per handler, retries, dead-letter queue, and
 * redeliveries that do not run a handler twice.
 */
@Import(OutboxRabbitIT.Handlers.class)
@TestPropertySource(properties = {
    "ebank.outbox.rabbitmq.enabled=true",
    "ebank.outbox.metrics-interval-ms=300",
    "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
    "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
class OutboxRabbitIT extends IntegrationTestBase {

    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.7-alpine");

    static {
        RABBIT.start();
    }

    static final String TYPE = "test.RabbitPing";

    record Ping(String value) {}

    static final AtomicInteger HEALTHY_CALLS = new AtomicInteger();
    static final AtomicInteger FLAKY_CALLS = new AtomicInteger();
    static final AtomicInteger BROKEN_CALLS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {

        @Bean
        OutboxEventHandler<Ping> rabbitHealthyHandler() {
            return new TestHandler("rabbit-healthy", HEALTHY_CALLS::incrementAndGet);
        }

        /** Fails twice, then succeeds: retried by the listener, never dead-lettered. */
        @Bean
        OutboxEventHandler<Ping> rabbitFlakyHandler() {
            return new TestHandler("rabbit-flaky", () -> {
                if (FLAKY_CALLS.incrementAndGet() <= 2) {
                    throw new IllegalStateException("downstream unavailable");
                }
            });
        }

        /** Always fails: ends in its dead-letter queue after the retries. */
        @Bean
        OutboxEventHandler<Ping> rabbitBrokenHandler() {
            return new TestHandler("rabbit-broken", () -> {
                BROKEN_CALLS.incrementAndGet();
                throw new IllegalStateException("bug");
            });
        }
    }

    record TestHandler(String name, Runnable action) implements OutboxEventHandler<Ping> {
        @Override
        public String eventType() {
            return TYPE;
        }

        @Override
        public Class<Ping> payloadType() {
            return Ping.class;
        }

        @Override
        public void handle(UUID eventId, Ping payload) {
            action.run();
        }

        @Override
        public String handlerName() {
            return "test." + name;
        }
    }

    @Autowired Outbox outbox;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired MeterRegistry meters;

    @Test
    void eachHandlerConsumesItsOwnQueueAndGivesUpIntoItsDeadLetterQueue() throws Exception {
        UUID id = tx.execute(status -> outbox.append(TYPE, "ping-1", new Ping("hello")));

        waitUntil(Duration.ofSeconds(30), () -> HEALTHY_CALLS.get() == 1 && FLAKY_CALLS.get() == 3 && deadLetters("rabbit-broken") == 1);

        // Published as soon as the broker confirmed it, whatever the handlers do afterwards.
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id)).isEqualTo("PUBLISHED");
        assertThat(HEALTHY_CALLS).as("not re-run while another handler retries").hasValue(1);
        assertThat(FLAKY_CALLS).as("two failures, then success").hasValue(3);
        assertThat(BROKEN_CALLS).as("1 attempt and 4 retries").hasValue(5);
        assertThat(deadLetters("rabbit-broken")).isEqualTo(1);
        assertThat(deadLetters("rabbit-flaky")).isZero();
        assertThat(jdbc.queryForList("SELECT handler FROM outbox_event_handled WHERE event_id = ? ORDER BY handler", String.class, id))
                .containsExactly("test.rabbit-flaky", "test.rabbit-healthy");
        waitUntil(Duration.ofSeconds(5), () -> deadLetterGauge("rabbit-broken") == 1);
        assertThat(deadLetterGauge("rabbit-broken")).isEqualTo(1);

        // The same event delivered again (a publisher retry, a broker redelivery): acknowledged, not re-run.
        MessageProperties props = new MessageProperties();
        props.setMessageId(id.toString());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send("ebank.events", TYPE, new Message("{\"value\":\"hello\"}".getBytes(StandardCharsets.UTF_8), props));
        waitUntil(Duration.ofSeconds(5), () -> messages("ebank.test.rabbit-healthy") == 0 && messages("ebank.test.rabbit-flaky") == 0);
        Thread.sleep(500);
        assertThat(HEALTHY_CALLS).hasValue(1);
        assertThat(FLAKY_CALLS).hasValue(3);
    }

    private long deadLetters(String handler) {
        return messages("ebank.test." + handler + ".dlq");
    }

    private long messages(String queue) {
        Properties info = admin.getQueueProperties(queue);
        return info == null ? -1 : ((Number) info.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
    }

    private double deadLetterGauge(String handler) {
        return meters.get("ebank.outbox.dead.letters").tag("handler", handler).gauge().value();
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(timeout);
        while (Instant.now().isBefore(end) && !condition.getAsBoolean()) {
            Thread.sleep(200);
        }
    }
}
