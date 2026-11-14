package com.ebank.accounts.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The accounts module's public API for other modules. */
public interface AccountsApi {

    /** Returns the account if the current user owns it or is an admin, otherwise throws a 404. */
    AccountView requireAccessible(String accountId);

    /** All account IDs owned by a user, including closed accounts. */
    Set<String> accountIdsOf(String userId);

    /**
     * A user's accounts, oldest first, including closed ones. Takes the user explicitly, for callers
     * running outside a request (such as the chat assistant). Never pass a user ID that did not come
     * from a verified token.
     */
    List<OwnedAccount> accountsOwnedBy(String userId);

    /**
     * Locks the accounts until the caller's transaction ends and returns those that exist.
     * Requires an active transaction. Locks are taken in a fixed order, so two callers locking
     * the same accounts cannot deadlock.
     */
    Map<String, AccountView> lock(Collection<String> accountIds);
}
