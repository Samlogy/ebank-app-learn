package com.ebank.transactions.application;

import java.math.BigDecimal;

public record DepositCommand(
        String receiverAccountId, BigDecimal amount, String currency, String reference, String idempotencyKey) {}
