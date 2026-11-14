package com.ebank.notifications.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebank.chatbot.api.HandoffRequested;
import com.ebank.notifications.application.OfflineChannel.Kind;
import com.ebank.notifications.application.UserDirectory.UserContact;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdvisorAlertsTest {

    private final UserDirectory directory = mock(UserDirectory.class);
    private final OfflineChannel email = mock(OfflineChannel.class);
    private final MutableClock clock = new MutableClock();

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private AdvisorAlerts alerts() {
        when(email.kind()).thenReturn(Kind.EMAIL);
        when(directory.usersWithRole("ADVISOR")).thenReturn(List.of(
                new UserContact("a@ebank.test", null), new UserContact(null, "+33600000000")));
        return new AdvisorAlerts(directory, List.of(email), clock);
    }

    private static HandoffRequested event() {
        return new HandoffRequested(UUID.randomUUID(), "USER_REQUEST", Instant.now());
    }

    @Test
    void emailsAdvisorsWithAVerifiedAddressAtMostOncePerQuietPeriod() {
        AdvisorAlerts alerts = alerts();

        alerts.onHandoff(event());
        alerts.onHandoff(event());
        verify(email, times(1)).sendText("a@ebank.test", AdvisorAlerts.SUBJECT, AdvisorAlerts.BODY);

        clock.now = clock.now.plus(AdvisorAlerts.QUIET_PERIOD);
        alerts.onHandoff(event());
        verify(email, times(2)).sendText(any(), any(), any());
    }

    @Test
    void failedSendIsRetriedByTheOutbox() {
        AdvisorAlerts alerts = alerts();
        doThrow(new IllegalStateException("smtp down")).doNothing().when(email).sendText(any(), any(), any());

        assertThatThrownBy(() -> alerts.onHandoff(event())).isInstanceOf(IllegalStateException.class);
        alerts.onHandoff(event());

        verify(email, times(2)).sendText(any(), any(), any());
    }

    @Test
    void failedDirectoryLookupIsRetriedToo() {
        AdvisorAlerts alerts = alerts();
        when(directory.usersWithRole("ADVISOR"))
                .thenThrow(new IllegalStateException("keycloak 403"))
                .thenReturn(List.of(new UserContact("a@ebank.test", null)));

        assertThatThrownBy(() -> alerts.onHandoff(event())).isInstanceOf(IllegalStateException.class);
        alerts.onHandoff(event());

        verify(email, times(1)).sendText("a@ebank.test", AdvisorAlerts.SUBJECT, AdvisorAlerts.BODY);
    }

    @Test
    void noEmailChannelMeansNoAlert() {
        new AdvisorAlerts(directory, List.of(), clock).onHandoff(event());
        verify(directory, never()).usersWithRole(any());
    }
}
