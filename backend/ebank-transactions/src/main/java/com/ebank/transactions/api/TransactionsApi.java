package com.ebank.transactions.api;

import com.ebank.common.money.Money;
import java.util.List;
import java.util.Optional;

/**
 * Read-only queries for other modules running outside a request (such as the chat assistant).
 * The user ID must come from a verified token, never from user or model input. Visibility is the
 * same as the REST API: a refused transfer is only visible to its sender.
 */
public interface TransactionsApi {

    /** Empty when the user does not own the account. */
    Optional<Money> balanceOf(String userId, String accountId);

    /**
     * Newest first.
     *
     * @param accountId null for all the user's accounts; an account they don't own gives an empty list
     * @param limit 1 to 50
     */
    List<TransferSummary> recentTransfers(String userId, String accountId, int limit);

    record TransferSummary(
            String transactionId,
            String senderAccountId,
            String receiverAccountId,
            Money money,
            String status,
            java.time.Instant createdAt) {}
}
