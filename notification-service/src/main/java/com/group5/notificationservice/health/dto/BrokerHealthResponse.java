package com.group5.notificationservice.health.dto;

import com.group5.notificationservice.health.BrokerHealth;
import com.group5.notificationservice.health.HealthStatus;

import java.time.OffsetDateTime;

public record BrokerHealthResponse(
        String name,
        HealthStatus status,
        OffsetDateTime lastCheckedAt,
        OffsetDateTime lastFailureAt,
        OffsetDateTime lastRecoveredAt) {

    public static BrokerHealthResponse from(BrokerHealth health) {
        return new BrokerHealthResponse(health.getName(), health.getStatus(), health.getLastCheckedAt(),
                health.getLastFailureAt(), health.getLastRecoveredAt());
    }
}
