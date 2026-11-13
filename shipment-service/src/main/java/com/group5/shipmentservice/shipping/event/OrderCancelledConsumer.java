package com.group5.shipmentservice.shipping.event;

import com.group5.shipmentservice.shipping.service.ShipmentStatusService;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class OrderCancelledConsumer {
    public static final String CONSUMER = "shipment-order-cancelled";
    private final IdempotentConsumer idempotency;
    private final ShipmentStatusService shipments;

    public OrderCancelledConsumer(IdempotentConsumer idempotency, ShipmentStatusService shipments) {
        this.idempotency = idempotency;
        this.shipments = shipments;
    }

    @KafkaListener(topics = Topics.COMMERCE, groupId = CONSUMER,
            autoStartup = "${nexus.shipping.order-cancelled-consumer-enabled:true}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        if (!"OrderCancelled".equals(event.eventType())) return;
        JsonNode payload = event.payload();
        if (payload == null || !payload.path("orderId").isString() || !payload.path("reason").isString()
                || payload.path("reason").asString().isBlank()) {
            throw new IllegalArgumentException("OrderCancelled requires orderId and reason");
        }
        UUID orderId = UUID.fromString(payload.path("orderId").asString());
        if (!orderId.toString().equals(event.aggregateId()) || !event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("OrderCancelled aggregate and Kafka key must match orderId");
        }
        idempotency.handle(CONSUMER, event, ignored -> shipments.cancelOrder(orderId,
                payload.path("reason").asString(), event.eventId(), event.traceId()));
    }
}
