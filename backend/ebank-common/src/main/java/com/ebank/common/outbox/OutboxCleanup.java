package com.ebank.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes PUBLISHED events once they are older than {@code ebank.outbox.retention}, whatever the transport. */
@Component
@ConditionalOnProperty(name = "ebank.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final OutboxStore store;

    OutboxCleanup(OutboxStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${ebank.outbox.cleanup-interval-ms:3600000}", initialDelayString = "${ebank.outbox.cleanup-interval-ms:3600000}")
    void deletePublished() {
        try {
            int deleted = store.deletePublished();
            if (deleted > 0) {
                log.info("Deleted {} published outbox events", deleted);
            }
        } catch (RuntimeException ex) {
            log.warn("Outbox cleanup failed: {}", ex.getClass().getSimpleName());
        }
    }
}
