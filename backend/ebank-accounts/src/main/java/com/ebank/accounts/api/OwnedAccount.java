package com.ebank.accounts.api;

import java.time.Instant;

/** A read-only description of one of a user's accounts, for other modules to display. */
public record OwnedAccount(String accountId, String type, String currency, String status, Instant createdAt) {}
