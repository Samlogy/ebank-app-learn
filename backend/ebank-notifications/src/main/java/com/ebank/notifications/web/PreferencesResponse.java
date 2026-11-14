package com.ebank.notifications.web;

import com.ebank.notifications.domain.NotificationPreference;
import java.time.Instant;

/** {@code updatedAt} is null until the user saves their own choice. */
record PreferencesResponse(boolean emailEnabled, boolean smsEnabled, Instant updatedAt) {

    static PreferencesResponse of(NotificationPreference preference) {
        return new PreferencesResponse(preference.isEmailEnabled(), preference.isSmsEnabled(), preference.getUpdatedAt());
    }
}
