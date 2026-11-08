package com.ebank.accounts.web;

import com.ebank.accounts.domain.AccountStatus;
import com.ebank.accounts.domain.AccountType;
import java.time.Instant;

public record AccountResponse(
        String accountId,
        AccountType type,
        AccountStatus status,
        String currency,
        String userId,
        Instant createdAt,
        Instant updatedAt,
        Long version) {}
