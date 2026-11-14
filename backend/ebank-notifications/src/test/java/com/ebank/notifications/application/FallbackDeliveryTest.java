package com.ebank.notifications.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebank.notifications.application.OfflineChannel.Kind;
import com.ebank.notifications.application.UserDirectory.UserContact;
import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.domain.NotificationPreference;
import com.ebank.notifications.infrastructure.persistence.NotificationPreferenceRepository;
import com.ebank.notifications.infrastructure.persistence.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FallbackDeliveryTest {

    @Mock NotificationRepository notifications;
    @Mock NotificationPreferenceRepository preferences;
    @Mock UserDirectory directory;
    @Mock Presence presence;
    @Mock LivePublisher live;
    @Mock OfflineChannel email;
    @Mock OfflineChannel sms;
    private FallbackDelivery delivery;
    private final Notification notification = TestData.received("bob");

    @BeforeEach
    void setUp() {
        lenient().when(email.kind()).thenReturn(Kind.EMAIL);
        lenient().when(sms.kind()).thenReturn(Kind.SMS);
        delivery = new FallbackDelivery(notifications, preferences, directory, presence, live, List.of(email, sms),
                new NotificationMetrics(new SimpleMeterRegistry()));
        lenient().when(notifications.findById(notification.getId())).thenReturn(Optional.of(notification));
        lenient().when(directory.find("bob")).thenReturn(Optional.of(new UserContact("bob@example.test", "+33600000000")));
    }

    @Test
    void defaultPreferencesSendAnEmailOnly() {
        delivery.deliver(notification.getId());

        verify(email).send(notification, "bob@example.test");
        verify(sms, never()).send(any(), any());
    }

    @Test
    void bothChannelsWhenBothAreOn() {
        when(preferences.findById("bob")).thenReturn(Optional.of(new NotificationPreference("bob", true, true)));

        delivery.deliver(notification.getId());

        verify(email).send(notification, "bob@example.test");
        verify(sms).send(notification, "+33600000000");
    }

    @Test
    void nothingIsSentWhenTheUserTurnedEverythingOff() {
        when(preferences.findById("bob")).thenReturn(Optional.of(new NotificationPreference("bob", false, false)));

        delivery.deliver(notification.getId());

        verifyNoInteractions(directory);
        verify(email, never()).send(any(), any());
    }

    @Test
    void alreadyReadNotificationIsNotSent() {
        notification.markRead(Instant.now());

        delivery.deliver(notification.getId());

        verifyNoInteractions(directory, live);
        verify(email, never()).send(any(), any());
    }

    @Test
    void userWhoCameOnlineGetsAPushInstead() {
        when(presence.isOnline("bob")).thenReturn(true);

        delivery.deliver(notification.getId());

        verify(live).push(any(), any());
        verify(email, never()).send(any(), any());
    }

    @Test
    void missingOrUnverifiedEmailIsSkipped() {
        when(directory.find("bob")).thenReturn(Optional.of(new UserContact(null, null)));

        delivery.deliver(notification.getId());

        verify(email, never()).send(any(), any());
    }

    @Test
    void failedSendIsRethrownSoTheOutboxRetries() {
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down")).when(email).send(any(), any());

        assertThatThrownBy(() -> delivery.deliver(notification.getId())).isInstanceOf(IllegalStateException.class);
    }
}
