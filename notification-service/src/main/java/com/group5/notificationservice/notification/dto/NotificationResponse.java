package com.group5.notificationservice.notification.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        String title,
        String content,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {

    public record Page(
            List<NotificationResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record UnreadCount(long count) {
    }
}
