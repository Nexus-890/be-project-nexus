package com.group5.notificationservice.eventlog.kafka.demo;

import java.time.Instant;
import java.util.UUID;

record KafkaDemoEvent(
        UUID eventId,
        String eventIdSource,
        String key,
        String topic,
        int partition,
        long offset,
        Instant receivedAt,
        String value
) {
}
