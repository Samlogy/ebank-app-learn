package com.ebank.transactions.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published through the outbox when a transfer or a deposit is booked (status DONE).
 *
 * @param senderUserId owner of the sender account, or null for a deposit from outside the bank
 * @param receiverUserId owner of the receiver account
 */
public record TransactionCompleted(
        String transactionId,
        String senderAccountId,
        String receiverAccountId,
        String senderUserId,
        String receiverUserId,
        BigDecimal amount,
        String currency,
        String reference,
        Instant occurredAt) {

    public static final String TYPE = "transactions.TransactionCompleted";

    public boolean isDeposit() {
        return senderUserId == null;
    }
}
