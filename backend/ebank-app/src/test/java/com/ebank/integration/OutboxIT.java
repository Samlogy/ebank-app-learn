package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebank.common.outbox.Outbox;
import com.ebank.common.outbox.OutboxEventHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Handlers of one event succeed or fail independently, and only failed ones are retried. */
@Import(OutboxIT.Handlers.class)
class OutboxIT extends IntegrationTestBase {

    static final String TYPE = "test.Ping";

    record Ping(String value) {}

    static final AtomicInteger HEALTHY_CALLS = new AtomicInteger();
    static final AtomicInteger FLAKY_CALLS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {

        @Bean
        OutboxEventHandler<Ping> healthyHandler() {
            return new TestHandler("healthy", () -> HEALTHY_CALLS.incrementAndGet());
        }

        /** Fails twice, then succeeds. */
        @Bean
        OutboxEventHandler<Ping> flakyHandler() {
            return new TestHandler("flaky", () -> {
                if (FLAKY_CALLS.incrementAndGet() <= 2) {
                    throw new IllegalStateException("downstream unavailable");
                }
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

    @Test
    void failingHandlerIsRetriedAloneUntilItSucceeds() throws InterruptedException {
        UUID id = tx.execute(status -> outbox.append(TYPE, "ping-1", new Ping("hello")));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        Map<String, Object> row;
        do {
            Thread.sleep(200);
            row = jdbc.queryForMap("SELECT status, attempts, last_error FROM outbox_event WHERE id = ?", id);
        } while (!"PUBLISHED".equals(row.get("status")) && Instant.now().isBefore(deadline));

        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("attempts")).isEqualTo(3);
        assertThat(row.get("last_error")).isNull();
        assertThat(FLAKY_CALLS.get()).isEqualTo(3);
        assertThat(HEALTHY_CALLS.get()).as("the healthy handler is not re-run on retries").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT handler FROM outbox_event_handled WHERE event_id = ? ORDER BY handler", String.class, id))
                .containsExactly("test.flaky", "test.healthy");
    }
}
