package com.ebank.accounts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebank.accounts.api.AccountClosingGuard;
import com.ebank.accounts.domain.Account;
import com.ebank.accounts.domain.AccountAction;
import com.ebank.accounts.domain.AccountHistory;
import com.ebank.accounts.domain.AccountStatus;
import com.ebank.accounts.domain.AccountType;
import com.ebank.accounts.infrastructure.persistence.AccountHistoryRepository;
import com.ebank.accounts.infrastructure.persistence.AccountRepository;
import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String ID = "EB001";

    @Mock AccountRepository accounts;
    @Mock AccountHistoryRepository history;
    @Mock CurrentUser currentUser;
    private final AccountClosingGuard guard = mock(AccountClosingGuard.class);
    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(accounts, history, List.of(guard), currentUser);
        lenient().when(currentUser.userId()).thenReturn("alice");
        lenient().doCallRealMethod().when(currentUser).requireAdmin();
    }

    private Account stored(String owner) {
        Account account = new Account(ID, AccountType.CURRENT, owner, "EUR");
        lenient().when(accounts.findByAccountId(ID)).thenReturn(Optional.of(account));
        lenient().when(accounts.findByAccountIdForUpdate(ID)).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void customerCannotSeeAnotherUsersAccount() {
        stored("bob");

        assertStatus(() -> service.get(ID), HttpStatus.NOT_FOUND);
    }

    @Test
    void ownerAndAdminCanSeeTheAccount() {
        Account own = stored("alice");
        assertThat(service.get(ID)).isSameAs(own);

        Account other = stored("bob");
        when(currentUser.isAdmin()).thenReturn(true);
        assertThat(service.get(ID)).isSameAs(other);
    }

    @Test
    void customerCannotActivate() {
        stored("alice");

        assertStatus(() -> service.activate(ID), HttpStatus.FORBIDDEN);
    }

    @Test
    void activatingRecordsHistoryWithTheAdminWhoDidIt() {
        when(currentUser.isAdmin()).thenReturn(true);
        when(currentUser.userId()).thenReturn("root");
        stored("alice");

        service.activate(ID);

        ArgumentCaptor<AccountHistory> saved = ArgumentCaptor.forClass(AccountHistory.class);
        verify(history).save(saved.capture());
        assertThat(saved.getValue().getAction()).isEqualTo(AccountAction.ACTIVATED);
        assertThat(saved.getValue().getStatusBefore()).isEqualTo(AccountStatus.CREATED);
        assertThat(saved.getValue().getStatusAfter()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getValue().getChangedBy()).isEqualTo("root");
    }

    @Test
    void guardCanVetoClosing() {
        Account account = stored("alice");
        account.activate();
        account.disable();
        doThrow(ApiException.conflict("ACCOUNT_BALANCE_NOT_ZERO", "x")).when(guard).checkCanClose(ID);

        assertStatus(() -> service.close(ID), HttpStatus.CONFLICT);
        verify(history, never()).save(any());
    }

    @Test
    void updatingToTheSameTypeRecordsNoHistory() {
        stored("alice");

        service.changeType(ID, AccountType.CURRENT);

        verify(history, never()).save(any());
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(status));
    }
}
