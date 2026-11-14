package com.ebank.common.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Polls the outbox and hands each pending event to the handlers of its type. Each event is
 * handled in its own transaction, so the handlers' writes and the PUBLISHED mark commit together.
 * Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several instances can run the relay.
 *
 * <p>This in-process relay stands in for a broker. When Kafka or RabbitMQ arrives, the relay
 * publishes to it instead and the handlers become consumers.
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
@ConditionalOnProperty(name = "ebank.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final OutboxProperties properties;
    private final Map<String, List<OutboxEventHandler<?>>> handlers;

    OutboxRelay(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            JsonMapper json,
            OutboxProperties properties,
            List<OutboxEventHandler<?>> handlers) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.groupingBy(OutboxEventHandler::eventType));
    }

    private record PendingEvent(UUID id, String type, String payload, int attempts) {}

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
        PendingEvent[] claimed = new PendingEvent[1];
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                List<PendingEvent> rows = jdbc.query("""
                        SELECT id, event_type, payload::text AS payload, attempts FROM outbox_event
                        WHERE status = 'PENDING' AND next_attempt_at <= ?
                        ORDER BY occurred_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """,
                        (rs, n) -> new PendingEvent(
                                rs.getObject("id", UUID.class),
                                rs.getString("event_type"),
                                rs.getString("payload"),
                                rs.getInt("attempts")),
                        Timestamp.from(Instant.now()));
                if (rows.isEmpty()) {
                    return false;
                }
                claimed[0] = rows.getFirst();
                dispatch(claimed[0]);
                jdbc.update("""
                        UPDATE outbox_event SET status = 'PUBLISHED', published_at = ?, attempts = attempts + 1, last_error = NULL
                        WHERE id = ?
                        """, Timestamp.from(Instant.now()), claimed[0].id());
                return true;
            }));
        } catch (RuntimeException ex) {
            if (claimed[0] == null) {
                log.warn("Outbox poll failed: {}", ex.getClass().getSimpleName());
                return false;
            }
            recordFailure(claimed[0], ex);
            return true;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(PendingEvent event) {
        List<OutboxEventHandler<?>> forType = handlers.getOrDefault(event.type(), List.of());
        if (forType.isEmpty()) {
            log.debug("Outbox event {} of type {} has no handler", event.id(), event.type());
        }
        for (OutboxEventHandler handler : forType) {
            handler.handle(event.id(), json.readValue(event.payload(), handler.payloadType()));
        }
    }

    /** Runs after the handler's transaction rolled back, so the attempt is recorded on its own. */
    private void recordFailure(PendingEvent event, RuntimeException ex) {
        int attempts = event.attempts() + 1;
        boolean givingUp = attempts >= properties.maxAttempts();
        // Only the exception type: messages can contain row values or personal data.
        String error = ex.getClass().getName();
        try {
            jdbc.update("""
                    UPDATE outbox_event SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ?
                    WHERE id = ? AND status = 'PENDING'
                    """,
                    givingUp ? "FAILED" : "PENDING",
                    attempts,
                    Timestamp.from(Instant.now().plus(properties.backoffAfter(attempts))),
                    error.length() > 255 ? error.substring(0, 255) : error,
                    event.id());
        } catch (RuntimeException updateFailure) {
            log.warn("Could not record the failure of outbox event {}: {}",
                    event.id(), updateFailure.getClass().getSimpleName());
        }
        if (givingUp) {
            log.error("Outbox event {} of type {} failed {} times and was marked FAILED", event.id(), event.type(), attempts, ex);
        } else {
            log.warn("Outbox event {} of type {} failed (attempt {}): {}", event.id(), event.type(), attempts, error);
        }
    }

    @Scheduled(fixedDelayString = "${ebank.outbox.cleanup-interval-ms:3600000}", initialDelayString = "${ebank.outbox.cleanup-interval-ms:3600000}")
    void deletePublished() {
        try {
            int deleted = jdbc.update("DELETE FROM outbox_event WHERE status = 'PUBLISHED' AND published_at < ?",
                    Timestamp.from(Instant.now().minus(properties.retention())));
            if (deleted > 0) {
                log.info("Deleted {} published outbox events", deleted);
            }
        } catch (RuntimeException ex) {
            log.warn("Outbox cleanup failed: {}", ex.getClass().getSimpleName());
        }
    }
}
