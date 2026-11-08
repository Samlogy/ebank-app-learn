package com.ebank.accounts.web;

import com.ebank.accounts.domain.AccountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotNull AccountType type,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter currency code") String currency) {}
