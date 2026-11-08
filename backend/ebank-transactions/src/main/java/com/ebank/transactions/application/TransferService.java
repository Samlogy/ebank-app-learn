package com.ebank.transactions.application;

import static com.ebank.transactions.infrastructure.persistence.TransferSpecifications.fieldEquals;
import static com.ebank.transactions.infrastructure.persistence.TransferSpecifications.involvesAccount;
import static com.ebank.transactions.infrastructure.persistence.TransferSpecifications.visibleToOwnerOf;

import com.ebank.accounts.api.AccountView;
import com.ebank.accounts.api.AccountsApi;
import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import com.ebank.common.money.Money;
import com.ebank.transactions.domain.EntryType;
import com.ebank.transactions.domain.LedgerEntry;
import com.ebank.transactions.domain.Transfer;
import com.ebank.transactions.infrastructure.persistence.LedgerEntryRepository;
import com.ebank.transactions.infrastructure.persistence.TransferRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String EXTERNAL_ACCOUNT_PREFIX = "EXTERNAL-";
    /** Limits how long a request can hold a connection while waiting for an account lock. */
    private static final int TIMEOUT_SECONDS = 10;

    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;
    private final AccountsApi accounts;
    private final CurrentUser currentUser;

    public TransferService(
            TransferRepository transfers, LedgerEntryRepository ledger, AccountsApi accounts, CurrentUser currentUser) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    /**
     * Moves money between two accounts. Without funds, the transfer is kept as CANCELLED with no
     * ledger entries, and a retry with the same key returns that same outcome.
     */
    @Transactional(timeout = TIMEOUT_SECONDS)
    public TransferResult transfer(TransferCommand command) {
        String userId = currentUser.userId();
        Money money = positive(command.amount(), command.currency());
        String senderId = command.senderAccountId();
        String receiverId = command.receiverAccountId();

        Optional<TransferResult> earlier =
                replay(userId, command.idempotencyKey(), senderId, receiverId, money, command.reference());
        if (earlier.isPresent()) {
            return earlier.get();
        }
        if (senderId.equals(receiverId)) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "The sender and receiver must be different accounts.");
        }

        // The balance is a sum over the ledger, so "check, then debit" is only safe while the
        // account is locked. Otherwise two simultaneous debits could both pass the check.
        Map<String, AccountView> locked = accounts.lock(List.of(senderId, receiverId));
        requireUsableSender(locked.get(senderId), userId, money);
        requireUsableReceiver(locked.get(receiverId), money);

        // Checked again: a request with the same key may have finished while this one waited for the lock.
        earlier = replay(userId, command.idempotencyKey(), senderId, receiverId, money, command.reference());
        if (earlier.isPresent()) {
            return earlier.get();
        }

        Transfer transfer = save(new Transfer(senderId, receiverId, money, command.reference(), command.idempotencyKey(), userId));
        if (balanceOf(senderId, money.currency()).isLessThan(money)) {
            transfer.cancel(INSUFFICIENT_FUNDS);
            log.info("Transfer {} cancelled: {}", transfer.getId(), INSUFFICIENT_FUNDS);
        } else {
            post(transfer, senderId, receiverId, money);
        }
        return new TransferResult(transfer, false);
    }

    /** Admin only: brings outside money into an account, balanced by an external ledger account. */
    @Transactional(timeout = TIMEOUT_SECONDS)
    public TransferResult deposit(DepositCommand command) {
        currentUser.requireAdmin();
        String userId = currentUser.userId();
        Money money = positive(command.amount(), command.currency());
        String senderId = EXTERNAL_ACCOUNT_PREFIX + money.currency();
        String receiverId = command.receiverAccountId();

        Map<String, AccountView> locked = accounts.lock(List.of(receiverId));
        Optional<TransferResult> earlier =
                replay(userId, command.idempotencyKey(), senderId, receiverId, money, command.reference());
        if (earlier.isPresent()) {
            return earlier.get();
        }
        requireUsableReceiver(locked.get(receiverId), money);

        Transfer transfer = save(new Transfer(senderId, receiverId, money, command.reference(), command.idempotencyKey(), userId));
        post(transfer, senderId, receiverId, money);
        return new TransferResult(transfer, false);
    }

    @Transactional(readOnly = true)
    public Transfer get(String transactionId) {
        return transfers.findOne(visibleToCaller().and(fieldEquals("transactionId", transactionId)))
                .orElseThrow(() -> ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found."));
    }

    @Transactional(readOnly = true)
    public Page<Transfer> search(TransferSearch search, Pageable pageable) {
        Specification<Transfer> filter = visibleToCaller()
                .and(fieldEquals("transactionId", search.transactionId()))
                .and(involvesAccount(search.accountId()))
                .and(fieldEquals("senderAccountId", search.sender()))
                .and(fieldEquals("receiverAccountId", search.receiver()));
        return transfers.findAll(filter, pageable);
    }

    @Transactional(readOnly = true)
    public Money balance(String accountId) {
        AccountView account = accounts.requireAccessible(accountId);
        return balanceOf(account.accountId(), account.currency());
    }

    private Specification<Transfer> visibleToCaller() {
        return currentUser.isAdmin()
                ? Specification.unrestricted()
                : visibleToOwnerOf(accounts.accountIdsOf(currentUser.userId()));
    }

    private Optional<TransferResult> replay(
            String userId, String key, String senderId, String receiverId, Money money, String reference) {
        return transfers.findByInitiatedByAndIdempotencyKey(userId, key).map(existing -> {
            if (!existing.isSameRequest(senderId, receiverId, money, reference)) {
                throw ApiException.unprocessable(
                        "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request.");
            }
            return new TransferResult(existing, true);
        });
    }

    private Transfer save(Transfer transfer) {
        // Flushed now so a duplicate idempotency key fails here, before any ledger entry is written.
        return transfers.saveAndFlush(transfer);
    }

    private void post(Transfer transfer, String senderId, String receiverId, Money money) {
        ledger.save(new LedgerEntry(transfer.getId(), senderId, EntryType.DEBIT, money));
        ledger.save(new LedgerEntry(transfer.getId(), receiverId, EntryType.CREDIT, money));
        transfer.complete();
        log.info("Transfer {} completed", transfer.getId());
    }

    private Money balanceOf(String accountId, String currency) {
        return new Money(ledger.balanceOf(accountId), currency);
    }

    private static Money positive(BigDecimal amount, String currency) {
        Money money = new Money(amount, currency);
        if (!money.isPositive()) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "The amount must be greater than zero.");
        }
        return money;
    }

    /** Admins get no exception here: moving a customer's money always takes the customer. */
    private static void requireUsableSender(AccountView sender, String userId, Money money) {
        if (sender == null || !sender.userId().equals(userId)) {
            throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found.");
        }
        if (!sender.active()) {
            throw ApiException.unprocessable("ACCOUNT_NOT_ACTIVE", "The sender account is not active.");
        }
        if (!sender.currency().equals(money.currency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "The sender account uses a different currency.");
        }
    }

    /** One message for every case, so this cannot be used to probe other people's accounts. */
    private static void requireUsableReceiver(AccountView receiver, Money money) {
        if (receiver == null || !receiver.active() || !receiver.currency().equals(money.currency())) {
            throw ApiException.unprocessable("RECEIVER_NOT_AVAILABLE", "The receiver account cannot accept this transfer.");
        }
    }
}
