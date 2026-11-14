package com.ebank.transactions.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebank.accounts.api.AccountView;
import com.ebank.accounts.api.AccountsApi;
import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import com.ebank.common.money.Money;
import com.ebank.common.outbox.Outbox;
import com.ebank.transactions.api.TransactionCancelled;
import com.ebank.transactions.api.TransactionCompleted;
import com.ebank.transactions.domain.EntryType;
import com.ebank.transactions.domain.LedgerEntry;
import com.ebank.transactions.domain.Transfer;
import com.ebank.transactions.domain.TransferStatus;
import com.ebank.transactions.infrastructure.persistence.LedgerEntryRepository;
import com.ebank.transactions.infrastructure.persistence.TransferRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock TransferRepository transfers;
    @Mock LedgerEntryRepository ledger;
    @Mock AccountsApi accounts;
    @Mock CurrentUser currentUser;
    @Mock Outbox outbox;
    private TransferService service;

    private static final AccountView ALICE = new AccountView("A", "alice", "EUR", true);
    private static final AccountView BOB = new AccountView("B", "bob", "EUR", true);

    @BeforeEach
    void setUp() {
        service = new TransferService(transfers, ledger, accounts, currentUser, outbox);
        lenient().when(currentUser.userId()).thenReturn("alice");
        lenient().doCallRealMethod().when(currentUser).requireAdmin();
        lenient().when(transfers.findByInitiatedByAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        lenient().when(transfers.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(accounts.lock(anyCollection())).thenReturn(Map.of("A", ALICE, "B", BOB));
    }

    private static TransferCommand command(String amount) {
        return new TransferCommand("A", "B", new BigDecimal(amount), "EUR", "rent", "key");
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test
    void completedTransferWritesBalancedLedgerEntries() {
        when(ledger.balanceOf("A")).thenReturn(new BigDecimal("100"));

        TransferResult result = service.transfer(command("30"));

        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.DONE);
        assertThat(result.replayed()).isFalse();
        ArgumentCaptor<LedgerEntry> entries = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledger, times(2)).save(entries.capture());
        List<LedgerEntry> saved = entries.getAllValues();
        assertThat(saved.get(0).getType()).isEqualTo(EntryType.DEBIT);
        assertThat(saved.get(0).getAccountId()).isEqualTo("A");
        assertThat(saved.get(1).getType()).isEqualTo(EntryType.CREDIT);
        assertThat(saved.get(1).getAccountId()).isEqualTo("B");
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo(saved.get(1).getAmount());
    }

    @Test
    void completedTransferPublishesAnEventNamingBothOwners() {
        when(ledger.balanceOf("A")).thenReturn(new BigDecimal("100"));

        Transfer transfer = service.transfer(command("30")).transfer();

        ArgumentCaptor<TransactionCompleted> event = ArgumentCaptor.forClass(TransactionCompleted.class);
        verify(outbox).append(eq(TransactionCompleted.TYPE), eq(transfer.getTransactionId()), event.capture());
        assertThat(event.getValue().senderUserId()).isEqualTo("alice");
        assertThat(event.getValue().receiverUserId()).isEqualTo("bob");
        assertThat(event.getValue().amount()).isEqualByComparingTo("30");
        assertThat(event.getValue().isDeposit()).isFalse();
    }

    @Test
    void spendingTheExactBalanceIsAllowed() {
        when(ledger.balanceOf("A")).thenReturn(new BigDecimal("30.00"));

        assertThat(service.transfer(command("30")).transfer().getStatus()).isEqualTo(TransferStatus.DONE);
    }

    @Test
    void insufficientFundsCancelsWithoutTouchingTheLedger() {
        when(ledger.balanceOf("A")).thenReturn(new BigDecimal("29.99"));

        Transfer transfer = service.transfer(command("30")).transfer();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(transfer.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(ledger, never()).save(any());
        ArgumentCaptor<TransactionCancelled> event = ArgumentCaptor.forClass(TransactionCancelled.class);
        verify(outbox).append(eq(TransactionCancelled.TYPE), eq(transfer.getTransactionId()), event.capture());
        assertThat(event.getValue().senderUserId()).isEqualTo("alice");
        assertThat(event.getValue().reason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void retryWithTheSameKeyReturnsTheOriginalWithoutLocking() {
        Transfer original = new Transfer("A", "B", new Money(new BigDecimal("30"), "EUR"), "rent", "key", "alice");
        when(transfers.findByInitiatedByAndIdempotencyKey("alice", "key")).thenReturn(Optional.of(original));

        TransferResult result = service.transfer(command("30"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isSameAs(original);
        verify(accounts, never()).lock(anyCollection());
    }

    @Test
    void sameKeyForADifferentRequestIsRejected() {
        Transfer original = new Transfer("A", "B", new Money(new BigDecimal("30"), "EUR"), "rent", "key", "alice");
        when(transfers.findByInitiatedByAndIdempotencyKey("alice", "key")).thenReturn(Optional.of(original));

        assertCode(() -> service.transfer(command("31")), "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void requestThatFinishedWhileWaitingForTheLockIsReplayed() {
        Transfer original = new Transfer("A", "B", new Money(new BigDecimal("30"), "EUR"), "rent", "key", "alice");
        when(transfers.findByInitiatedByAndIdempotencyKey("alice", "key"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(original));

        assertThat(service.transfer(command("30")).replayed()).isTrue();
        verify(transfers, never()).saveAndFlush(any());
        verify(outbox, never()).append(any(), any(), any());
    }

    @Test
    void cannotSendFromAnotherUsersAccountEvenAsAdmin() {
        lenient().when(currentUser.isAdmin()).thenReturn(true);
        when(currentUser.userId()).thenReturn("root");

        assertCode(() -> service.transfer(command("30")), "ACCOUNT_NOT_FOUND");
    }

    @Test
    void validatesAccountsAndAmounts() {
        assertCode(() -> service.transfer(command("0")), "INVALID_AMOUNT");
        assertCode(() -> service.transfer(command("-5")), "INVALID_AMOUNT");
        assertCode(() -> service.transfer(command("1.005")), "INVALID_AMOUNT");
        assertCode(() -> service.transfer(new TransferCommand("A", "A", BigDecimal.ONE, "EUR", null, "k")), "SAME_ACCOUNT");

        when(accounts.lock(anyCollection())).thenReturn(Map.of("A", new AccountView("A", "alice", "EUR", false), "B", BOB));
        assertCode(() -> service.transfer(command("1")), "ACCOUNT_NOT_ACTIVE");

        when(accounts.lock(anyCollection())).thenReturn(Map.of("A", ALICE));
        assertCode(() -> service.transfer(command("1")), "RECEIVER_NOT_AVAILABLE");

        when(accounts.lock(anyCollection())).thenReturn(Map.of("A", ALICE, "B", new AccountView("B", "bob", "EUR", false)));
        assertCode(() -> service.transfer(command("1")), "RECEIVER_NOT_AVAILABLE");

        when(accounts.lock(anyCollection())).thenReturn(Map.of("A", ALICE, "B", new AccountView("B", "bob", "USD", true)));
        assertCode(() -> service.transfer(command("1")), "RECEIVER_NOT_AVAILABLE");
    }

    @Test
    void depositRequiresAdmin() {
        assertCode(() -> service.deposit(new DepositCommand("B", BigDecimal.TEN, "EUR", null, "k")), "FORBIDDEN");
        verify(accounts, never()).lock(anyCollection());
    }

    @Test
    void depositIsBalancedByTheExternalAccount() {
        when(currentUser.isAdmin()).thenReturn(true);

        Transfer transfer = service.deposit(new DepositCommand("B", BigDecimal.TEN, "EUR", null, "k")).transfer();

        assertThat(transfer.getSenderAccountId()).isEqualTo("EXTERNAL-EUR");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.DONE);
        verify(ledger, times(2)).save(any());
        verify(ledger, never()).balanceOf(anyString());
        ArgumentCaptor<TransactionCompleted> event = ArgumentCaptor.forClass(TransactionCompleted.class);
        verify(outbox).append(eq(TransactionCompleted.TYPE), any(), event.capture());
        assertThat(event.getValue().isDeposit()).isTrue();
        assertThat(event.getValue().receiverUserId()).isEqualTo("bob");
    }
}
