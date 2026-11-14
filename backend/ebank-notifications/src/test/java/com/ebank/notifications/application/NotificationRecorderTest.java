package com.ebank.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebank.common.outbox.Outbox;
import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.infrastructure.persistence.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class NotificationRecorderTest {

    @Mock NotificationRepository notifications;
    @Mock Outbox outbox;
    @Mock Presence presence;
    @Mock LivePublisher live;
    private NotificationRecorder recorder;
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recorder = new NotificationRecorder(notifications, outbox, presence, live, new NotificationMetrics(new SimpleMeterRegistry()));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void onlineUserGetsALivePushOnlyAfterCommit() {
        when(presence.isOnline("bob")).thenReturn(true);

        recorder.onTransactionCompleted(eventId, TestData.completed(null, "bob"));

        verify(notifications).save(any(Notification.class));
        verify(live, never()).push(any(), any());
        commit();
        ArgumentCaptor<NotificationView> pushed = ArgumentCaptor.forClass(NotificationView.class);
        verify(live).push(eq("bob"), pushed.capture());
        assertThat(pushed.getValue().transactionId()).isEqualTo("TXN1");
        verify(outbox, never()).append(any(), any(), any());
    }

    @Test
    void offlineUserGetsAFallbackRequestInTheSameTransaction() {
        when(presence.isOnline("bob")).thenReturn(false);

        recorder.onTransactionCompleted(eventId, TestData.completed(null, "bob"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(saved.capture());
        verify(outbox).append(FallbackRequested.TYPE, saved.getValue().getId().toString(),
                new FallbackRequested(saved.getValue().getId()));
        commit();
        verify(live, never()).push(any(), any());
    }

    @Test
    void replayedEventIsNotRecordedTwice() {
        when(notifications.existsBySourceEventIdAndUserId(eventId, "bob")).thenReturn(true);

        recorder.onTransactionCompleted(eventId, TestData.completed(null, "bob"));

        verify(notifications, never()).save(any());
        verify(outbox, never()).append(any(), any(), any());
    }
}
