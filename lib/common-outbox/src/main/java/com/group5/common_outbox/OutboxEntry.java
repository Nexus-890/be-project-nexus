package com.group5.common_outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEntry(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        String topic,
        String payload,
        String traceId,
        Instant occurredAt,
        Instant publishedAt
) {
}
