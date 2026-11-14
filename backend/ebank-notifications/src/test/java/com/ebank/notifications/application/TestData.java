package com.ebank.notifications.application;

import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.domain.NotificationType;
import com.ebank.transactions.api.TransactionCompleted;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class TestData {

    private TestData() {}

    static TransactionCompleted completed(String senderUser, String receiverUser) {
        return new TransactionCompleted("TXN1", "FR7600000000001111", "FR7600000000002222",
                senderUser, receiverUser, new BigDecimal("30.50000000"), "EUR", "rent", Instant.parse("2026-11-14T10:00:00Z"));
    }

    static Notification received(String userId) {
        return new Notification(userId, NotificationType.TRANSFER_RECEIVED, UUID.randomUUID(), "TXN1",
                "FR7600000000002222", "FR7600000000001111", new BigDecimal("30.50000000"), "EUR", Instant.now());
    }
}
