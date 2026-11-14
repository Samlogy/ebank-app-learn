package com.ebank.notifications.infrastructure.persistence;

import com.ebank.notifications.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, String> {}
