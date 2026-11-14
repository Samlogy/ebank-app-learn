package com.ebank.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTest {

    @Test
    void markingReadTwiceKeepsTheFirstTime() {
        Notification notification = new Notification("bob", NotificationType.TRANSFER_RECEIVED, UUID.randomUUID(),
                "TXN1", "A", "B", BigDecimal.ONE, "EUR", Instant.now());
        Instant first = Instant.parse("2026-11-14T10:00:00Z");

        notification.markRead(first);
        notification.markRead(first.plusSeconds(60));

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isEqualTo(first);
    }

    @Test
    void defaultsAreEmailOnSmsOff() {
        NotificationPreference preference = NotificationPreference.defaults("bob");

        assertThat(preference.isEmailEnabled()).isTrue();
        assertThat(preference.isSmsEnabled()).isFalse();
    }
}
