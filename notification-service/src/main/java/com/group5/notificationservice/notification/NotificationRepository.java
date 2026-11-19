package com.group5.notificationservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND n.inAppEnabled = true")
    Page<Notification> findByUserId(UUID userId, Pageable pageable);

    @Query("SELECT count(n) FROM Notification n WHERE n.userId = :userId AND n.readAt IS NULL AND n.inAppEnabled = true")
    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n SET n.readAt = :readAt, n.updatedBy = :userId
            WHERE n.id = :id AND n.userId = :userId AND n.readAt IS NULL AND n.inAppEnabled = true
            """)
    int markAsRead(@Param("id") UUID id, @Param("userId") UUID userId,
                   @Param("readAt") OffsetDateTime readAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n SET n.readAt = :readAt, n.updatedBy = :userId
            WHERE n.userId = :userId AND n.readAt IS NULL AND n.inAppEnabled = true
            """)
    int markAllAsRead(@Param("userId") UUID userId, @Param("readAt") OffsetDateTime readAt);
}
