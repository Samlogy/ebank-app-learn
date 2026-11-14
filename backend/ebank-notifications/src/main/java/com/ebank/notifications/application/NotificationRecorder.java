package com.ebank.notifications.application;

import com.ebank.common.outbox.Outbox;
import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.infrastructure.persistence.NotificationRepository;
import com.ebank.transactions.api.TransactionCancelled;
import com.ebank.transactions.api.TransactionCompleted;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns domain events into stored notifications, then pushes each one live if its user is
 * connected, or asks for an email or SMS fallback if not.
 */
@Service
public class NotificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecorder.class);

    private final NotificationRepository notifications;
    private final Outbox outbox;
    private final Presence presence;
    private final LivePublisher live;
    private final NotificationMetrics metrics;

    NotificationRecorder(
            NotificationRepository notifications,
            Outbox outbox,
            Presence presence,
            LivePublisher live,
            NotificationMetrics metrics) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.presence = presence;
        this.live = live;
        this.metrics = metrics;
    }

    /** Called by the outbox relay, inside its transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTransactionCompleted(UUID eventId, TransactionCompleted event) {
        record(NotificationFactory.forCompleted(eventId, event));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onTransactionCancelled(UUID eventId, TransactionCancelled event) {
        record(NotificationFactory.forCancelled(eventId, event));
    }

    private void record(List<Notification> created) {
        for (Notification notification : created) {
            // The same event can be delivered twice. The unique constraint backs this check.
            if (notifications.existsBySourceEventIdAndUserId(notification.getSourceEventId(), notification.getUserId())) {
                log.debug("Notification for event {} already recorded", notification.getSourceEventId());
                continue;
            }
            notifications.save(notification);
            metrics.created(notification.getType());

            if (presence.isOnline(notification.getUserId())) {
                pushAfterCommit(notification);
            } else {
                outbox.append(FallbackRequested.TYPE, notification.getId().toString(),
                        new FallbackRequested(notification.getId()));
            }
        }
    }

    /** A push before the commit could announce a notification that then rolls back. */
    private void pushAfterCommit(Notification notification) {
        String userId = notification.getUserId();
        NotificationView view = NotificationView.of(notification);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    live.push(userId, view);
                    metrics.delivery("live", "sent");
                } catch (RuntimeException ex) {
                    // Still stored, so the user sees it on their next load.
                    metrics.delivery("live", "failed");
                    log.warn("Live push of notification {} failed: {}", view.id(), ex.getClass().getSimpleName());
                }
            }
        });
    }
}
