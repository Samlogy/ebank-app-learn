package com.ebank.analytics.application;

import com.ebank.common.money.Currencies;
import com.ebank.transactions.api.TransactionCancelled;
import com.ebank.transactions.api.TransactionCompleted;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One transaction as analytics sees it. Stored once per transaction ID, so a redelivered event
 * overwrites itself instead of being counted twice.
 *
 * <p>The amount is kept in minor units of the currency (cents, satoshis) as a long, so sums are
 * exact: Elasticsearch sums in doubles, which hold every integer up to 2^53 exactly.
 * The transfer reference is left out: it is free text written by another person.
 *
 * @param participants user IDs allowed to see this fact (sender and receiver owners)
 * @param internal both sides belong to the same user
 */
public record TransactionFact(
        String transactionId,
        Kind kind,
        Status status,
        String senderAccountId,
        String receiverAccountId,
        String senderUserId,
        String receiverUserId,
        List<String> participants,
        boolean internal,
        String currency,
        long amountMinor,
        int scale,
        String failureReason,
        Instant occurredAt) {

    public enum Kind { TRANSFER, DEPOSIT }

    public enum Status { DONE, CANCELLED }

    public static TransactionFact of(TransactionCompleted event) {
        return new TransactionFact(
                event.transactionId(),
                event.isDeposit() ? Kind.DEPOSIT : Kind.TRANSFER,
                Status.DONE,
                event.senderAccountId(),
                event.receiverAccountId(),
                event.senderUserId(),
                event.receiverUserId(),
                participants(event.senderUserId(), event.receiverUserId()),
                event.senderUserId() != null && event.senderUserId().equals(event.receiverUserId()),
                event.currency(),
                toMinor(event.amount(), event.currency()),
                Currencies.maxScale(event.currency()),
                null,
                event.occurredAt());
    }

    /** The receiver is not a participant: no money reached them, and they were never told. */
    public static TransactionFact of(TransactionCancelled event) {
        return new TransactionFact(
                event.transactionId(),
                Kind.TRANSFER,
                Status.CANCELLED,
                event.senderAccountId(),
                event.receiverAccountId(),
                event.senderUserId(),
                null,
                participants(event.senderUserId(), null),
                false,
                event.currency(),
                toMinor(event.amount(), event.currency()),
                Currencies.maxScale(event.currency()),
                event.reason(),
                event.occurredAt());
    }

    /** Throws if the amount has more decimals than the currency allows: that would be a bug upstream. */
    static long toMinor(BigDecimal amount, String currency) {
        return amount.movePointRight(Currencies.maxScale(currency)).setScale(0).longValueExact();
    }

    private static List<String> participants(String sender, String receiver) {
        List<String> users = new ArrayList<>(2);
        if (sender != null) {
            users.add(sender);
        }
        if (receiver != null && !receiver.equals(sender)) {
            users.add(receiver);
        }
        return List.copyOf(users);
    }
}
