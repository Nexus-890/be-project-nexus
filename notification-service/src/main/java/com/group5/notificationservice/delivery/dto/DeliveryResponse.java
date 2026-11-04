package com.group5.notificationservice.delivery.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DeliveryResponse(
        UUID id,
        String channel,
        String status,
        int attemptCount,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<Attempt> attempts) {

    public record Attempt(
            UUID id,
            int attemptNumber,
            String status,
            OffsetDateTime attemptedAt,
            OffsetDateTime completedAt,
            String errorCode,
            boolean retryable) {
    }
}
