package com.ebank.common.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a domain event in the caller's transaction. The event is only delivered if that
 * transaction commits, and it is delivered even if the application stops right after the commit.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    Outbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param eventType a stable name, such as {@code transactions.TransactionCompleted}
     * @param aggregateId the business ID the event is about, kept for tracing and ordering
     * @param payload a record that Jackson can serialize. It must not contain secrets.
     * @return the event ID, which handlers receive as their idempotency key
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String eventType, String aggregateId, Object payload) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO outbox_event (id, event_type, aggregate_id, payload, occurred_at, status, attempts, next_attempt_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, 'PENDING', 0, ?)
                """, id, eventType, aggregateId, json.writeValueAsString(payload), now, now);
        return id;
    }
}
