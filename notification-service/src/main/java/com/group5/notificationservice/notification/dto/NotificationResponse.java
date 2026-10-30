package com.group5.notificationservice.notification.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        String title,
        String content,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {

    public record UnreadCount(long count) {
    }
}
