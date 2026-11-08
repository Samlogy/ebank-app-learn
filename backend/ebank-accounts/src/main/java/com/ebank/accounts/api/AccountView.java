package com.ebank.accounts.api;

/** What other modules may know about an account. */
public record AccountView(String accountId, String userId, String currency, boolean active) {}
