package com.ebank.transactions.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published through the outbox when a transfer is refused after it was recorded, such as for
 * insufficient funds. No money moved, so only the sender is concerned.
 */
public record TransactionCancelled(
        String transactionId,
        String senderAccountId,
        String receiverAccountId,
        String senderUserId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt) {

    public static final String TYPE = "transactions.TransactionCancelled";
}
