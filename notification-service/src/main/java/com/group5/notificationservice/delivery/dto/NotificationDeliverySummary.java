package com.group5.notificationservice.delivery.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationDeliverySummary(UUID id, UUID notificationId, UUID userId, String type, String title,
        String channel, String status, int attemptCount, OffsetDateTime nextAttemptAt,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, String lastErrorCode) {}
