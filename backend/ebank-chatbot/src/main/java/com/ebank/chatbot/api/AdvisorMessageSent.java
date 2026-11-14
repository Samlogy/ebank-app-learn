package com.ebank.chatbot.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published through the outbox when an advisor writes to a customer who is not in the chat right
 * now, so notifications can tell them. Carries no message content.
 */
public record AdvisorMessageSent(UUID conversationId, String customerId, Instant sentAt) {

    public static final String TYPE = "chatbot.AdvisorMessageSent";
}
