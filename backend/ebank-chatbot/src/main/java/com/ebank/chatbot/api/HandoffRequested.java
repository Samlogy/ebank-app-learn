package com.ebank.chatbot.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published through the outbox when a conversation joins the queue while no advisor is online,
 * so advisors can be alerted by email. Carries nothing about the customer.
 */
public record HandoffRequested(UUID conversationId, String reason, Instant requestedAt) {

    public static final String TYPE = "chatbot.HandoffRequested";
}
