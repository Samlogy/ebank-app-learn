package com.ebank.notifications.application;

import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.domain.NotificationType;
import com.ebank.transactions.api.TransactionCancelled;
import com.ebank.transactions.api.TransactionCompleted;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Decides who is told what about a transaction. */
final class NotificationFactory {

    private NotificationFactory() {}

    static List<Notification> forCompleted(UUID eventId, TransactionCompleted event) {
        List<Notification> result = new ArrayList<>(2);
        String sender = event.senderUserId();
        String receiver = event.receiverUserId();

        if (sender != null && sender.equals(receiver)) {
            // Both sides belong to the same person: one notification, not a "sent" and a "received".
            result.add(notification(sender, NotificationType.TRANSFER_INTERNAL, eventId, event,
                    event.senderAccountId(), event.receiverAccountId()));
            return result;
        }
        if (sender != null) {
            result.add(notification(sender, NotificationType.TRANSFER_SENT, eventId, event,
                    event.senderAccountId(), event.receiverAccountId()));
        }
        if (receiver != null) {
            result.add(notification(receiver, NotificationType.TRANSFER_RECEIVED, eventId, event,
                    event.receiverAccountId(), event.senderAccountId()));
        }
        return result;
    }

    static List<Notification> forCancelled(UUID eventId, TransactionCancelled event) {
        if (event.senderUserId() == null) {
            return List.of();
        }
        return List.of(new Notification(
                event.senderUserId(),
                NotificationType.TRANSFER_CANCELLED,
                eventId,
                event.transactionId(),
                event.senderAccountId(),
                event.receiverAccountId(),
                event.amount(),
                event.currency(),
                event.occurredAt()));
    }

    private static Notification notification(
            String userId, NotificationType type, UUID eventId, TransactionCompleted event, String own, String other) {
        return new Notification(
                userId, type, eventId, event.transactionId(), own, other, event.amount(), event.currency(), event.occurredAt());
    }
}
