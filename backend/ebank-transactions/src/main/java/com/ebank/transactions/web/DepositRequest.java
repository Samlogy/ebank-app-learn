package com.ebank.transactions.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record DepositRequest(
        @NotBlank @Size(max = 34) String receiverAccountId,
        @NotNull BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter currency code") String currency,
        @Size(max = 140) String reference) {}
