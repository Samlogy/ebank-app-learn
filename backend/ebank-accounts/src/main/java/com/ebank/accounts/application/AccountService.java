package com.ebank.accounts.application;

import com.ebank.accounts.api.AccountClosingGuard;
import com.ebank.accounts.api.AccountView;
import com.ebank.accounts.api.AccountsApi;
import com.ebank.accounts.domain.Account;
import com.ebank.accounts.domain.AccountAction;
import com.ebank.accounts.domain.AccountHistory;
import com.ebank.accounts.domain.AccountIdGenerator;
import com.ebank.accounts.domain.AccountStatus;
import com.ebank.accounts.domain.AccountType;
import com.ebank.accounts.infrastructure.persistence.AccountHistoryRepository;
import com.ebank.accounts.infrastructure.persistence.AccountRepository;
import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements AccountsApi {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final AccountHistoryRepository history;
    private final List<AccountClosingGuard> closingGuards;
    private final CurrentUser currentUser;

    public AccountService(
            AccountRepository accounts,
            AccountHistoryRepository history,
            List<AccountClosingGuard> closingGuards,
            CurrentUser currentUser) {
        this.accounts = accounts;
        this.history = history;
        this.closingGuards = closingGuards;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<Account> list(Pageable pageable) {
        return currentUser.isAdmin()
                ? accounts.findAll(pageable)
                : accounts.findByUserId(currentUser.userId(), pageable);
    }

    @Transactional(readOnly = true)
    public Account get(String accountId) {
        return accessible(accounts.findByAccountId(accountId));
    }

    @Transactional(readOnly = true)
    public Page<AccountHistory> history(String accountId, Pageable pageable) {
        Account account = get(accountId);
        return history.findByAccountId(account.getAccountId(), pageable);
    }

    @Transactional
    public Account create(AccountType type, String currency) {
        String userId = currentUser.userId();
        Account account = accounts.save(new Account(AccountIdGenerator.next(), type, userId, currency));
        record(account, AccountAction.CREATED, null, null);
        log.info("Account {} created with status {}", account.getId(), account.getStatus());
        return account;
    }

    @Transactional
    public Account changeType(String accountId, AccountType newType) {
        Account account = get(accountId);
        AccountType typeBefore = account.getType();
        account.changeType(newType);
        if (typeBefore != newType) {
            record(account, AccountAction.TYPE_CHANGED, account.getStatus(), typeBefore);
        }
        return account;
    }

    /** Only an admin can activate, so re-enabling a disabled account also needs one. */
    @Transactional
    public Account activate(String accountId) {
        currentUser.requireAdmin();
        Account account = lockAccessible(accountId);
        AccountStatus statusBefore = account.getStatus();
        account.activate();
        record(account, AccountAction.ACTIVATED, statusBefore, account.getType());
        log.info("Account {} activated", account.getId());
        return account;
    }

    @Transactional
    public Account disable(String accountId) {
        Account account = lockAccessible(accountId);
        AccountStatus statusBefore = account.getStatus();
        account.disable();
        record(account, AccountAction.DISABLED, statusBefore, account.getType());
        log.info("Account {} disabled", account.getId());
        return account;
    }

    /** Soft delete: the row and its history stay. The row lock blocks transfers until this commits. */
    @Transactional
    public Account close(String accountId) {
        Account account = lockAccessible(accountId);
        AccountStatus statusBefore = account.getStatus();
        account.close();
        closingGuards.forEach(guard -> guard.checkCanClose(account.getAccountId()));
        record(account, AccountAction.CLOSED, statusBefore, account.getType());
        log.info("Account {} closed", account.getId());
        return account;
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView requireAccessible(String accountId) {
        return toView(get(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> accountIdsOf(String userId) {
        return Set.copyOf(accounts.findAccountIdsByUserId(userId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, AccountView> lock(Collection<String> accountIds) {
        Map<String, AccountView> locked = new LinkedHashMap<>();
        for (String accountId : new TreeSet<>(accountIds)) {
            accounts.findByAccountIdForUpdate(accountId).ifPresent(account -> locked.put(accountId, toView(account)));
        }
        return locked;
    }

    private Account lockAccessible(String accountId) {
        return accessible(accounts.findByAccountIdForUpdate(accountId));
    }

    /** Someone else's account looks exactly like one that does not exist. */
    private Account accessible(Optional<Account> account) {
        return account.filter(found -> currentUser.isAdmin() || found.getUserId().equals(currentUser.userId()))
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
    }

    private void record(Account account, AccountAction action, AccountStatus statusBefore, AccountType typeBefore) {
        history.save(new AccountHistory(account, action, statusBefore, typeBefore, currentUser.userId()));
    }

    private static AccountView toView(Account account) {
        return new AccountView(
                account.getAccountId(),
                account.getUserId(),
                account.getCurrency(),
                account.getStatus() == AccountStatus.ACTIVE);
    }
}
