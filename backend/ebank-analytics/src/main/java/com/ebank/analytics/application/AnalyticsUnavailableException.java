package com.ebank.analytics.application;

/** The analytics store is down or too slow. Indexing is retried by the outbox; queries get a 503. */
public class AnalyticsUnavailableException extends RuntimeException {

    public AnalyticsUnavailableException(Throwable cause) {
        super("Analytics store unavailable", cause);
    }
}
