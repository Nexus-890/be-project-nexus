package com.group5.notificationservice.delivery.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record NotificationDeliveryDetail(UUID id, UUID notificationId, UUID userId, String type, String title,
        String channel, String status, int attemptCount, OffsetDateTime nextAttemptAt,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, String lastErrorCode, List<DeliveryResponse.Attempt> attempts) {
    public NotificationDeliveryDetail(NotificationDeliverySummary row, List<DeliveryResponse.Attempt> attempts) {
        this(row.id(), row.notificationId(), row.userId(), row.type(), row.title(), row.channel(), row.status(),
                row.attemptCount(), row.nextAttemptAt(), row.createdAt(), row.updatedAt(), row.lastErrorCode(), attempts);
    }
}
