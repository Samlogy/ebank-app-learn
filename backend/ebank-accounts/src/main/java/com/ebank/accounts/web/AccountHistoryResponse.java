package com.ebank.accounts.web;

import com.ebank.accounts.domain.AccountAction;
import com.ebank.accounts.domain.AccountStatus;
import com.ebank.accounts.domain.AccountType;
import java.time.Instant;

public record AccountHistoryResponse(
        AccountAction action,
        AccountStatus statusBefore,
        AccountStatus statusAfter,
        AccountType typeBefore,
        AccountType typeAfter,
        String changedBy,
        Instant changedAt) {}
