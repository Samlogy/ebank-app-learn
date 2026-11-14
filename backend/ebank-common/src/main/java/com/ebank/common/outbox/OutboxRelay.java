package com.ebank.common.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Polls the outbox and hands each pending event to the handlers of its type.
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
 * <p>This in-process relay stands in for a broker. When Kafka or RabbitMQ arrives, the relay
 * publishes to it instead and each handler becomes a consumer group.
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
        PendingEvent event;
        try {
            event = claimNext();
        } catch (RuntimeException ex) {
            log.warn("Outbox poll failed: {}", ex.getClass().getSimpleName());
            return false;
        }
        if (event == null) {
            return false;
        }

        RuntimeException failure = null;
        Set<String> done = alreadyHandled(event.id());
        for (OutboxEventHandler<?> handler : handlers.getOrDefault(event.type(), List.of())) {
            if (done.contains(handler.handlerName())) {
                continue;
            }
            try {
                handleOne(event, handler);
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
                jdbc.update("""
                        UPDATE outbox_event SET status = 'PUBLISHED', published_at = ?, attempts = attempts + 1, last_error = NULL
                        WHERE id = ?
                        """, Timestamp.from(Instant.now()), event.id());
            } else {
                recordFailure(event, failure);
            }
        } catch (RuntimeException ex) {
            // The lease runs out and the event is retried. Handlers that succeeded are skipped.
            log.warn("Could not update outbox event {}: {}", event.id(), ex.getClass().getSimpleName());
        }
        return true;
    }

    /** One statement, so the row lock lasts only as long as the claim itself. */
    private PendingEvent claimNext() {
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

    private Set<String> alreadyHandled(UUID eventId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT handler FROM outbox_event_handled WHERE event_id = ?", String.class, eventId));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleOne(PendingEvent event, OutboxEventHandler handler) {
        tx.executeWithoutResult(status -> {
            handler.handle(event.id(), json.readValue(event.payload(), handler.payloadType()));
            jdbc.update("""
                    INSERT INTO outbox_event_handled (event_id, handler, handled_at) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, event.id(), handler.handlerName(), Timestamp.from(Instant.now()));
        });
    }

    private void recordFailure(PendingEvent event, RuntimeException ex) {
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

    @Scheduled(fixedDelayString = "${ebank.outbox.cleanup-interval-ms:3600000}", initialDelayString = "${ebank.outbox.cleanup-interval-ms:3600000}")
    void deletePublished() {
        try {
            // outbox_event_handled rows go with their event (ON DELETE CASCADE).
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
