package com.ebank.transactions.application;

import static com.ebank.transactions.infrastructure.persistence.TransferSpecifications.involvesAccount;
import static com.ebank.transactions.infrastructure.persistence.TransferSpecifications.visibleToOwnerOf;

import com.ebank.accounts.api.AccountsApi;
import com.ebank.common.money.Money;
import com.ebank.transactions.api.TransactionsApi;
import com.ebank.transactions.domain.Transfer;
import com.ebank.transactions.infrastructure.persistence.LedgerEntryRepository;
import com.ebank.transactions.infrastructure.persistence.TransferRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TransactionQueries implements TransactionsApi {

    private static final int MAX_LIMIT = 50;

    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;
    private final AccountsApi accounts;

    TransactionQueries(TransferRepository transfers, LedgerEntryRepository ledger, AccountsApi accounts) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Money> balanceOf(String userId, String accountId) {
        return accounts.accountsOwnedBy(userId).stream()
                .filter(account -> account.accountId().equals(accountId))
                .findFirst()
                .map(account -> new Money(ledger.balanceOf(accountId), account.currency()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferSummary> recentTransfers(String userId, String accountId, int limit) {
        Set<String> owned = accounts.accountIdsOf(userId);
        if (accountId != null && !owned.contains(accountId)) {
            return List.of();
        }
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        return transfers.findAll(
                        visibleToOwnerOf(owned).and(involvesAccount(accountId)),
                        PageRequest.of(0, size, Sort.by("createdAt").descending()))
                .map(TransactionQueries::summary)
                .getContent();
    }

    private static TransferSummary summary(Transfer t) {
        return new TransferSummary(
                t.getTransactionId(), t.getSenderAccountId(), t.getReceiverAccountId(), t.money(), t.getStatus().name(), t.getCreatedAt());
    }
}
