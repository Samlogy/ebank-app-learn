package com.ebank.notifications.application;

import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.domain.NotificationPreference;
import com.ebank.notifications.infrastructure.persistence.NotificationPreferenceRepository;
import com.ebank.notifications.infrastructure.persistence.NotificationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The caller's own notifications. Admins get no wider access: notifications are personal. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final CurrentUser currentUser;

    NotificationService(
            NotificationRepository notifications, NotificationPreferenceRepository preferences, CurrentUser currentUser) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(boolean unreadOnly, Pageable pageable) {
        String userId = currentUser.userId();
        return unreadOnly
                ? notifications.findByUserIdAndReadAtIsNull(userId, pageable)
                : notifications.findByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.countByUserIdAndReadAtIsNull(currentUser.userId());
    }

    /** Someone else's notification gets the same 404 as a missing one. */
    @Transactional
    public Notification markRead(UUID id) {
        Notification notification = notifications.findByIdAndUserId(id, currentUser.userId())
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found."));
        notification.markRead(Instant.now());
        return notification;
    }

    @Transactional
    public int markAllRead() {
        return notifications.markAllRead(currentUser.userId(), Instant.now());
    }

    @Transactional(readOnly = true)
    public NotificationPreference preferences() {
        String userId = currentUser.userId();
        return preferences.findById(userId).orElseGet(() -> NotificationPreference.defaults(userId));
    }

    @Transactional
    public NotificationPreference updatePreferences(boolean emailEnabled, boolean smsEnabled) {
        String userId = currentUser.userId();
        NotificationPreference preference = preferences.findById(userId)
                .orElseGet(() -> NotificationPreference.defaults(userId));
        preference.change(emailEnabled, smsEnabled);
        return preferences.save(preference);
    }
}
