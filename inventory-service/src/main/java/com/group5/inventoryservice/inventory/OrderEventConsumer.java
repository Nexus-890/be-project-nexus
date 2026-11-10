package com.group5.inventoryservice.inventory;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.inventoryservice.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ReservationService reservations;
    private final JsonMapper mapper;

    @KafkaListener(topics = Topics.COMMERCE, groupId = "${nexus.inventory.consumer-group:inventory-order-events}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        if ("OrderPaid".equals(event.eventType())) {
            orderPaid(typed(event, OrderPaid.class));
        }
    }

    private void orderPaid(EventEnvelope<OrderPaid> event) {
        OrderPaid paid = event.payload();
        int confirmed = reservations.confirmOrder(paid.orderId());
        log.info("ORDER_PAID_STOCK_CONFIRMED eventId={} orderId={} orderType={} reservations={}",
                event.eventId(), paid.orderId(), paid.orderType(), confirmed);
    }

    private <T> EventEnvelope<T> typed(EventEnvelope<JsonNode> event, Class<T> payloadType) {
        return new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), mapper.convertValue(event.payload(), payloadType));
    }
}
