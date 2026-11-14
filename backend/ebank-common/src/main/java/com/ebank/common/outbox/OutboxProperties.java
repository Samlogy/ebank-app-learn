package com.ebank.common.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param batchSize events delivered per poll at most, so one poll cannot hold the scheduler for long
 * @param maxAttempts after this many failures an event is marked FAILED and left for investigation
 * @param maxBackoff upper bound of the exponential delay between two attempts
 * @param retention how long PUBLISHED events are kept before they are deleted
 */
@ConfigurationProperties("ebank.outbox")
record OutboxProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("15m") Duration maxBackoff,
        @DefaultValue("7d") Duration retention) {

    /** 2, 4, 8... seconds after each failure, capped at {@link #maxBackoff}. */
    Duration backoffAfter(int attempts) {
        long seconds = 1L << Math.min(Math.max(attempts, 1), 20);
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
