package com.group5.notificationservice.health.dto;

import java.util.List;

public record SystemHealthResponse(List<ServiceHealthResponse> services, List<BrokerStatusResponse> brokers) {
}
