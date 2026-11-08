package com.ebank.accounts.web;

import com.ebank.accounts.domain.AccountType;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountRequest(@NotNull AccountType type) {}
