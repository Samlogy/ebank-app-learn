package com.ebank.notifications.application;

import com.ebank.notifications.application.OfflineChannel.Kind;
import com.ebank.notifications.application.UserDirectory.UserContact;
import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.domain.NotificationPreference;
import com.ebank.notifications.infrastructure.persistence.NotificationPreferenceRepository;
import com.ebank.notifications.infrastructure.persistence.NotificationRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reaches a user who was offline when a notification was created, using the channels they
 * turned on. Runs from the outbox, so a failing send is retried with backoff.
 *
 * <p>When email and SMS are both on and only SMS fails, the retry sends the email again:
 * the outbox retries the whole event. Accepted for now, because SMS has no provider yet.
 */
@Service
public class FallbackDelivery {

    private static final Logger log = LoggerFactory.getLogger(FallbackDelivery.class);

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final UserDirectory directory;
    private final Presence presence;
    private final LivePublisher live;
    private final Map<Kind, OfflineChannel> channels = new EnumMap<>(Kind.class);
    private final NotificationMetrics metrics;

    FallbackDelivery(
            NotificationRepository notifications,
            NotificationPreferenceRepository preferences,
            UserDirectory directory,
            Presence presence,
            LivePublisher live,
            List<OfflineChannel> channels,
            NotificationMetrics metrics) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.directory = directory;
        this.presence = presence;
        this.live = live;
        channels.forEach(channel -> this.channels.put(channel.kind(), channel));
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public void deliver(UUID notificationId) {
        Optional<Notification> found = notifications.findById(notificationId);
        if (found.isEmpty()) {
            log.warn("Fallback for unknown notification {} skipped", notificationId);
            return;
        }
        Notification notification = found.get();
        String userId = notification.getUserId();

        if (notification.isRead()) {
            metrics.delivery("fallback", "skipped");
            return;
        }
        // The user may have connected since: a push is better than an email.
        if (presence.isOnline(userId)) {
            live.push(userId, NotificationView.of(notification));
            metrics.delivery("live", "sent");
            return;
        }

        NotificationPreference preference = preferences.findById(userId).orElseGet(() -> NotificationPreference.defaults(userId));
        if (!preference.isEmailEnabled() && !preference.isSmsEnabled()) {
            metrics.delivery("fallback", "skipped");
            return;
        }
        Optional<UserContact> contact = directory.find(userId);
        if (contact.isEmpty()) {
            metrics.delivery("fallback", "skipped");
            log.info("No contact details for the owner of notification {}, fallback skipped", notificationId);
            return;
        }
        if (preference.isEmailEnabled()) {
            send(Kind.EMAIL, notification, contact.get().email());
        }
        if (preference.isSmsEnabled()) {
            send(Kind.SMS, notification, contact.get().phone());
        }
    }

    private void send(Kind kind, Notification notification, String address) {
        String channelName = kind.name().toLowerCase(java.util.Locale.ROOT);
        OfflineChannel channel = channels.get(kind);
        if (channel == null || address == null || address.isBlank()) {
            metrics.delivery(channelName, "skipped");
            log.debug("No {} channel or address for notification {}", channelName, notification.getId());
            return;
        }
        try {
            channel.send(notification, address);
            metrics.delivery(channelName, "sent");
            log.info("Notification {} sent by {}", notification.getId(), channelName);
        } catch (RuntimeException ex) {
            metrics.delivery(channelName, "failed");
            throw ex;
        }
    }
}
