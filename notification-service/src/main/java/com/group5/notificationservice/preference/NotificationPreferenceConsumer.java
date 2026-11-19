package com.group5.notificationservice.preference;

import com.group5.common_events.*;
import com.group5.common_outbox.IdempotentConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

@Component
public class NotificationPreferenceConsumer {
    private final NotificationPreferenceService preferences;
    private final IdempotentConsumer idempotent;
    public NotificationPreferenceConsumer(NotificationPreferenceService preferences, IdempotentConsumer idempotent) {
        this.preferences = preferences; this.idempotent = idempotent;
    }
    @KafkaListener(topics = Topics.USER, groupId = "notification-preferences", containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        var event = record.value();
        if (!"UserRegistered".equals(event.eventType())) return;
        UUID userId = UUID.fromString(event.payload().path("userId").asString());
        if (!userId.toString().equals(event.aggregateId()) || !userId.toString().equals(record.key()))
            throw new IllegalArgumentException("UserRegistered identity mismatch");
        idempotent.handle("notification-preferences", event, ignored -> preferences.initialize(userId));
    }
}
