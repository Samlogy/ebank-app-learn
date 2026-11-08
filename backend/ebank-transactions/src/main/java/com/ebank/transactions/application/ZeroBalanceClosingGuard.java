package com.ebank.transactions.application;

import com.ebank.accounts.api.AccountClosingGuard;
import com.ebank.common.error.ApiException;
import com.ebank.transactions.infrastructure.persistence.LedgerEntryRepository;
import org.springframework.stereotype.Component;

/** An account still holding money cannot be closed. */
@Component
class ZeroBalanceClosingGuard implements AccountClosingGuard {

    private final LedgerEntryRepository ledger;

    ZeroBalanceClosingGuard(LedgerEntryRepository ledger) {
        this.ledger = ledger;
    }

    @Override
    public void checkCanClose(String accountId) {
        if (ledger.balanceOf(accountId).signum() != 0) {
            throw ApiException.conflict("ACCOUNT_BALANCE_NOT_ZERO", "An account with a balance cannot be closed.");
        }
    }
}
