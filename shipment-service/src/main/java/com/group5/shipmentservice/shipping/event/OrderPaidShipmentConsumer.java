package com.group5.shipmentservice.shipping.event;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.shipmentservice.shipping.event.payload.OrderPaidSnapshot;
import com.group5.shipmentservice.shipping.service.ShipmentCreationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OrderPaidShipmentConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderPaidShipmentConsumer.class);
    public static final String CONSUMER = "shipment-order-paid";
    private final IdempotentConsumer idempotency;
    private final ShipmentCreationService shipments;
    private final JsonMapper mapper;

    public OrderPaidShipmentConsumer(IdempotentConsumer idempotency, ShipmentCreationService shipments, JsonMapper mapper) {
        this.idempotency = idempotency; this.shipments = shipments; this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.COMMERCE, groupId = CONSUMER,
            autoStartup = "${nexus.shipping.order-paid-consumer-enabled:true}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        if (!"OrderPaid".equals(event.eventType())) return;
        OrderPaidSnapshot snapshot = OrderPaidSnapshot.parse(event.payload(), mapper);
        if (!snapshot.orderId().toString().equals(event.aggregateId()) || !event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("OrderPaid aggregate and Kafka key must match orderId");
        }
        var result = new AtomicReference<ShipmentCreationService.CreationResult>();
        idempotency.handle(CONSUMER, event, ignored -> result.set(shipments.createPaid(event, snapshot)));
        var created = result.get();
        if (created != null && created.created()) {
            try {
                shipments.assignInitialTracking(created.id());
            } catch (RuntimeException error) {
                log.warn("Initial tracking update rolled back; worker will retry shipmentId={}", created.id(), error);
            }
        }
    }
}
