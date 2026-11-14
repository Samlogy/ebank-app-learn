package com.ebank.notifications.application;

import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.domain.NotificationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What the REST API returns and what is pushed to {@code /user/queue/notifications}. */
public record NotificationView(
        UUID id,
        NotificationType type,
        String transactionId,
        String accountId,
        String counterparty,
        BigDecimal amount,
        String currency,
        Instant createdAt,
        boolean read) {

    public static NotificationView of(Notification notification) {
        return new NotificationView(
                notification.getId(),
                notification.getType(),
                notification.getSourceId(),
                notification.getAccountId(),
                notification.getCounterparty(),
                notification.getAmount(),
                notification.getCurrency(),
                notification.getCreatedAt(),
                notification.isRead());
    }
}
