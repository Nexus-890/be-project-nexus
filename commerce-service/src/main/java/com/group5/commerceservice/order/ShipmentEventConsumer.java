package com.group5.commerceservice.order;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShipmentEventConsumer {
    private final ShipmentStatusHandler shipments;
    private final JsonMapper mapper;

    @KafkaListener(topics = Topics.SHIPMENT, groupId = "commerce-shipment-events",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        if (!"ShipmentPickedUp".equals(event.eventType()) && !"ShipmentDelivered".equals(event.eventType())) {
            log.debug("Shipment event has no order transition: eventId={}, eventType={}",
                    event.eventId(), event.eventType());
            return;
        }
        ShipmentStatusChanged payload = mapper.convertValue(event.payload(), ShipmentStatusChanged.class);
        if (payload.orderId() == null || !payload.orderId().toString().equals(record.key())) {
            throw new IllegalArgumentException("Shipment event key must be its orderId");
        }
        shipments.handle(new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(),
                event.occurredAt(), event.traceId(), payload));
    }
}
