package com.ebank.notifications.infrastructure.persistence;

import com.ebank.notifications.domain.Notification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserId(String userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNull(String userId, Pageable pageable);

    Optional<Notification> findByIdAndUserId(UUID id, String userId);

    long countByUserIdAndReadAtIsNull(String userId);

    boolean existsBySourceEventIdAndUserId(UUID sourceEventId, String userId);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") String userId, @Param("now") Instant now);
}
