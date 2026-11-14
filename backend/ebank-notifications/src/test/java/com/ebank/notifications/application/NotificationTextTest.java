package com.ebank.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebank.notifications.domain.Notification;
import org.junit.jupiter.api.Test;

class NotificationTextTest {

    @Test
    void textUsesTheCurrencyScaleAndMasksAccounts() {
        Notification notification = TestData.received("bob");

        assertThat(NotificationText.subject(notification)).isEqualTo("You received 30.50 EUR");
        assertThat(NotificationText.body(notification))
                .contains("••••2222")
                .doesNotContain("FR7600000000002222")
                .doesNotContain("rent");
    }

    @Test
    void externalAccountsAreNamedNotMasked() {
        assertThat(NotificationText.masked("EXTERNAL-EUR")).isEqualTo("outside EBANK");
        assertThat(NotificationText.masked("ABC")).isEqualTo("••••");
    }
}
