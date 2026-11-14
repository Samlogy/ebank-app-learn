package com.ebank.analytics.application;

import com.ebank.transactions.api.TransactionCancelled;
import com.ebank.transactions.api.TransactionCompleted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Write side: turns transaction events into facts. Called by the outbox relay; a failure (store
 * down) is retried with backoff, and saving the same fact twice is harmless.
 */
@Service
@ConditionalOnProperty(name = "ebank.analytics.enabled", havingValue = "true", matchIfMissing = true)
public class TransactionProjector {

    private final AnalyticsStore store;
    private final MeterRegistry meters;

    TransactionProjector(AnalyticsStore store, MeterRegistry meters) {
        this.store = store;
        this.meters = meters;
    }

    public void onCompleted(TransactionCompleted event) {
        save(TransactionFact.of(event));
    }

    public void onCancelled(TransactionCancelled event) {
        save(TransactionFact.of(event));
    }

    private void save(TransactionFact fact) {
        store.save(fact);
        Counter.builder("ebank.analytics.indexed")
                .description("Transaction facts written to the analytics store")
                .tag("status", fact.status().name())
                .register(meters)
                .increment();
    }
}
