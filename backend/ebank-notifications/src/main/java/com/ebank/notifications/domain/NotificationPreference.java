package com.ebank.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** How a user wants to be told about notifications while they are not connected. */
@Entity
@Table(name = "notification_preference")
public class NotificationPreference {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "sms_enabled", nullable = false)
    private boolean smsEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected NotificationPreference() {}

    public NotificationPreference(String userId, boolean emailEnabled, boolean smsEnabled) {
        this.userId = userId;
        this.emailEnabled = emailEnabled;
        this.smsEnabled = smsEnabled;
    }

    /** What applies until the user saves their own choice. */
    public static NotificationPreference defaults(String userId) {
        return new NotificationPreference(userId, true, false);
    }

    public void change(boolean emailEnabled, boolean smsEnabled) {
        this.emailEnabled = emailEnabled;
        this.smsEnabled = smsEnabled;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getUserId() {
        return userId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public boolean isSmsEnabled() {
        return smsEnabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
