package com.ebank.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.domain.NotificationType;
import com.ebank.transactions.api.TransactionCancelled;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationFactoryTest {

    private final UUID eventId = UUID.randomUUID();

    @Test
    void transferBetweenTwoPeopleNotifiesBothSides() {
        List<Notification> result = NotificationFactory.forCompleted(eventId, TestData.completed("alice", "bob"));

        assertThat(result).extracting(Notification::getUserId, Notification::getType, Notification::getAccountId, Notification::getCounterparty)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("alice", NotificationType.TRANSFER_SENT, "FR7600000000001111", "FR7600000000002222"),
                        org.assertj.core.groups.Tuple.tuple("bob", NotificationType.TRANSFER_RECEIVED, "FR7600000000002222", "FR7600000000001111"));
        assertThat(result).allSatisfy(n -> {
            assertThat(n.getSourceEventId()).isEqualTo(eventId);
            assertThat(n.getSourceId()).isEqualTo("TXN1");
            assertThat(n.isRead()).isFalse();
        });
    }

    @Test
    void transferBetweenOwnAccountsGivesOneInternalNotification() {
        List<Notification> result = NotificationFactory.forCompleted(eventId, TestData.completed("alice", "alice"));

        assertThat(result).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_INTERNAL);
            assertThat(n.getUserId()).isEqualTo("alice");
        });
    }

    @Test
    void depositOnlyNotifiesTheReceiver() {
        List<Notification> result = NotificationFactory.forCompleted(eventId, TestData.completed(null, "bob"));

        assertThat(result).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_RECEIVED);
            assertThat(n.getUserId()).isEqualTo("bob");
        });
    }

    @Test
    void cancelledTransferOnlyNotifiesTheSender() {
        TransactionCancelled event = new TransactionCancelled("TXN2", "A", "B", "alice",
                BigDecimal.TEN, "EUR", "INSUFFICIENT_FUNDS", Instant.now());

        assertThat(NotificationFactory.forCancelled(eventId, event)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_CANCELLED);
            assertThat(n.getUserId()).isEqualTo("alice");
        });
    }
}
