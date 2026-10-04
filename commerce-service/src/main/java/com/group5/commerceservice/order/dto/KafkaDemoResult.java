package com.group5.commerceservice.order.dto;

import java.util.UUID;

public record KafkaDemoResult(UUID outboxId, String eventId, String key, String topic, int partition, long offset) {
}
