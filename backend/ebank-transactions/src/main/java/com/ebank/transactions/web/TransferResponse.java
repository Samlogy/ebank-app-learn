package com.ebank.transactions.web;

import com.ebank.transactions.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        String transactionId,
        String senderAccountId,
        String receiverAccountId,
        BigDecimal amount,
        String currency,
        String reference,
        TransferStatus status,
        String failureReason,
        Instant createdAt) {}
