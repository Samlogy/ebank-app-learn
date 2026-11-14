package com.ebank.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code ebank_outbox_events{status="PENDING"|"FAILED"}} and {@code ebank_outbox_oldest_pending_seconds}.
 * Counted on a timer rather than on every scrape, so a busy Prometheus cannot load the database.
 */
@Component
class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    OutboxMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder("ebank.outbox.events", pending, AtomicLong::get).tag("status", "PENDING")
                .description("Outbox events waiting for delivery").register(meters);
        Gauge.builder("ebank.outbox.events", failed, AtomicLong::get).tag("status", "FAILED")
                .description("Outbox events given up after too many attempts").register(meters);
        Gauge.builder("ebank.outbox.oldest.pending", oldestPendingSeconds, AtomicLong::get).baseUnit("seconds")
                .description("Age of the oldest event waiting for delivery").register(meters);
    }

    @Scheduled(fixedDelayString = "${ebank.outbox.metrics-interval-ms:30000}", initialDelay = 5000)
    void refresh() {
        try {
            long p = 0;
            long f = 0;
            Instant oldest = null;
            for (var row : jdbc.queryForList("SELECT status, count(*) AS n, min(occurred_at) AS oldest FROM outbox_event "
                    + "WHERE status IN ('PENDING', 'FAILED') GROUP BY status")) {
                long n = ((Number) row.get("n")).longValue();
                if ("PENDING".equals(row.get("status"))) {
                    p = n;
                    oldest = row.get("oldest") instanceof Timestamp t ? t.toInstant() : null;
                } else {
                    f = n;
                }
            }
            pending.set(p);
            failed.set(f);
            oldestPendingSeconds.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds()));
        } catch (RuntimeException ex) {
            log.debug("Outbox metrics not refreshed: {}", ex.getClass().getSimpleName());
        }
    }
}
