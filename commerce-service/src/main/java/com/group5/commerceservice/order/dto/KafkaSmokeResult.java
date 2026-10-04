package com.group5.commerceservice.order.dto;

public record KafkaSmokeResult(String eventId, String key, String topic, int partition, long offset) {
}
