package com.group5.notificationservice.health.dto;

import com.group5.notificationservice.health.HealthStatus;

public record ServiceHealthResponse(String name, HealthStatus status) {
}
