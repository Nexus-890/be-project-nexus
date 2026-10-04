package com.group5.notificationservice.eventlog.kafka.smoke;

import java.time.Instant;

public record KafkaSmokeMessage(
        String eventId,
        String key,
        String topic,
        int partition,
        long offset,
        Instant receivedAt,
        String value
) {
}
