package com.group5.notificationservice.health.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.group5.notificationservice.health.HealthStatus;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessageBrokerUnavailable(
        String brokerName,
        String environment,
        HealthStatus previousStatus,
        HealthStatus status,
        long healthVersion,
        Instant checkedAt,
        Instant lastFailureAt,
        Instant lastRecoveredAt,
        String reasonCode) {
}
