package com.ebank.transactions.application;

import java.math.BigDecimal;

public record TransferCommand(
        String senderAccountId,
        String receiverAccountId,
        BigDecimal amount,
        String currency,
        String reference,
        String idempotencyKey) {}
