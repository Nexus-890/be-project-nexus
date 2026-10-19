package com.group5.common_events;

import java.time.Instant;
import java.util.UUID;
//Write follow EDA, use to box a event have metadata like Id event, type, traceId....
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String aggregateId,
        Instant occurredAt,
        String traceId,
        T payload
) {

    public EventEnvelope {
        requirePresent(eventId, "eventId");
        requireText(eventType, "eventType");
        requireText(aggregateId, "aggregateId");
        requirePresent(occurredAt, "occurredAt");
        requireText(traceId, "traceId");
        requirePresent(payload, "payload");
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
