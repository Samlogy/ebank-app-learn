package com.ebank.accounts.api;

/**
 * Lets other modules veto closing an account, without the accounts module depending on them.
 * Called while the account's row is locked.
 */
public interface AccountClosingGuard {

    /** Throws if the account cannot be closed. */
    void checkCanClose(String accountId);
}
