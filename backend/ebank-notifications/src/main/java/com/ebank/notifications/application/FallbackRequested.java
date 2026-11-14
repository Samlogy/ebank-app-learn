package com.ebank.notifications.application;

import java.util.UUID;

/**
 * Written to the outbox when a notification could not be pushed live. Going through the outbox
 * gives email and SMS sending retries with backoff, without holding the notification's transaction open.
 */
public record FallbackRequested(UUID notificationId) {

    public static final String TYPE = "notifications.FallbackRequested";
}
