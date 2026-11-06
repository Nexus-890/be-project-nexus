package com.group5.notificationservice.delivery;

import java.time.Instant;
import java.util.UUID;

public final class DeliveryEvents {

    private DeliveryEvents() {
    }

    public record Sent(UUID notificationId, UUID deliveryId, UUID attemptId, UUID recipientId, String channel,
                       int attemptNumber, String providerMessageId, Instant sentAt) {
    }

    public record Failed(UUID notificationId, UUID deliveryId, UUID attemptId, UUID recipientId, String channel,
                         int attemptNumber, String errorCode, boolean retryable, String deliveryStatus,
                         Instant nextAttemptAt, Instant failedAt) {
    }
}
