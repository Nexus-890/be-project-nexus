package com.group5.notificationservice.health.dto;

import com.group5.notificationservice.health.BrokerHealth;
import com.group5.notificationservice.health.HealthStatus;

import java.time.OffsetDateTime;

/**
 * Bản ngắn của broker, nằm trong response của GET /api/v1/system/health. Endpoint
 * GET /api/v1/system/health/brokers trả {@link BrokerHealthResponse}, có thêm lastFailureAt và lastRecoveredAt.
 */
public record BrokerStatusResponse(String name, HealthStatus status, OffsetDateTime lastCheckedAt) {

    public static BrokerStatusResponse from(BrokerHealth health) {
        return new BrokerStatusResponse(health.getName(), health.getStatus(), health.getLastCheckedAt());
    }
}
