package com.ebank.common.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox tables, shared by both transports: the in-process relay ({@link OutboxRelay}) and
 * RabbitMQ ({@link RabbitOutboxPublisher}, {@link RabbitOutboxConsumer}).
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final OutboxProperties properties;

    OutboxStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, JsonMapper json, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
        this.properties = properties;
    }

    record PendingEvent(UUID id, String type, String payload, int attempts) {}

    /**
     * Claims the oldest pending event with a lease: {@code next_attempt_at} is pushed forward, so
     * other instances skip it while it is delivered. One statement, so the row lock lasts only as
     * long as the claim itself. Returns null when nothing is due.
     */
    PendingEvent claimNext() {
        Instant now = Instant.now();
        List<PendingEvent> rows = jdbc.query("""
                UPDATE outbox_event SET next_attempt_at = ?
                WHERE id = (
                    SELECT id FROM outbox_event
                    WHERE status = 'PENDING' AND next_attempt_at <= ?
                    ORDER BY occurred_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, event_type, payload::text AS payload, attempts
                """,
                (rs, n) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts")),
                Timestamp.from(now.plus(properties.lease())),
                Timestamp.from(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Set<String> alreadyHandled(UUID eventId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT handler FROM outbox_event_handled WHERE event_id = ?", String.class, eventId));
    }

    /**
     * Runs one handler in its own transaction, together with the row that records its success.
     * Returns false, without calling it, when this handler already handled the event (a redelivery).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean handle(UUID eventId, String payload, OutboxEventHandler handler) {
        Boolean ran = tx.execute(status -> {
            // Locks out a concurrent delivery of the same event to the same handler until commit.
            int inserted = jdbc.update("""
                    INSERT INTO outbox_event_handled (event_id, handler, handled_at) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, eventId, handler.handlerName(), Timestamp.from(Instant.now()));
            if (inserted == 0) {
                return false;
            }
            handler.handle(eventId, json.readValue(payload, handler.payloadType()));
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }

    void markPublished(UUID eventId) {
        jdbc.update("""
                UPDATE outbox_event SET status = 'PUBLISHED', published_at = ?, attempts = attempts + 1, last_error = NULL
                WHERE id = ?
                """, Timestamp.from(Instant.now()), eventId);
    }

    void recordFailure(PendingEvent event, Exception ex) {
        int attempts = event.attempts() + 1;
        boolean givingUp = attempts >= properties.maxAttempts();
        // Only the exception type: messages can contain row values or personal data.
        String error = ex.getClass().getName();
        jdbc.update("""
                UPDATE outbox_event SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ?
                WHERE id = ? AND status = 'PENDING'
                """,
                givingUp ? "FAILED" : "PENDING",
                attempts,
                Timestamp.from(Instant.now().plus(properties.backoffAfter(attempts))),
                error.length() > 255 ? error.substring(0, 255) : error,
                event.id());
        if (givingUp) {
            log.error("Outbox event {} of type {} failed {} times and was marked FAILED", event.id(), event.type(), attempts, ex);
        } else {
            log.warn("Outbox event {} of type {} failed (attempt {}): {}", event.id(), event.type(), attempts, error);
        }
    }

    int deletePublished() {
        // outbox_event_handled rows go with their event (ON DELETE CASCADE).
        return jdbc.update("DELETE FROM outbox_event WHERE status = 'PUBLISHED' AND published_at < ?",
                Timestamp.from(Instant.now().minus(properties.retention())));
    }
}
