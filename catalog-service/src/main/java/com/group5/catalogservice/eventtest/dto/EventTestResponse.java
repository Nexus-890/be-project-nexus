package com.group5.catalogservice.eventtest.dto;

import java.util.UUID;

public record EventTestResponse(
        UUID eventId,
        String eventType,
        String traceId,
        String topic,
        int partition,
        long offset
) {
}
