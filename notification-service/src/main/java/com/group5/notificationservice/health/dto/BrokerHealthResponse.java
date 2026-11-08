package com.group5.notificationservice.health.dto;

import com.group5.notificationservice.health.BrokerHealth;
import com.group5.notificationservice.health.HealthStatus;

import java.time.Instant;

public record BrokerHealthResponse(
        String name,
        HealthStatus status,
        Instant lastCheckedAt,
        Instant lastFailureAt,
        Instant lastRecoveredAt) {

    public static BrokerHealthResponse from(BrokerHealth health) {
        return new BrokerHealthResponse(health.name(), health.status(), health.lastCheckedAt(),
                health.lastFailureAt(), health.lastRecoveredAt());
    }
}
