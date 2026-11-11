package com.group5.inventoryservice.inventory;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.inventoryservice.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    public static final String LISTENER_ID = "inventory-order-events";
    static final String ORDER_PAID_CONSUMER = "inventory-order-paid";

    private final IdempotentConsumer idempotent;
    private final ReservationService reservations;
    private final JsonMapper mapper;

    @KafkaListener(id = LISTENER_ID, topics = Topics.COMMERCE,
            groupId = "${nexus.inventory.consumer-group:inventory-order-events}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        if ("OrderPaid".equals(event.eventType())) {
            idempotent.handle(ORDER_PAID_CONSUMER, orderPaidOf(event), this::orderPaid);
        }
    }

    private void orderPaid(EventEnvelope<OrderPaid> event) {
        OrderPaid paid = event.payload();
        int confirmed = reservations.confirmOrder(paid.orderId());
        if (confirmed == 0) {
            log.info("ORDER_PAID_NO_RESERVATION eventId={} orderId={} orderType={}",
                    event.eventId(), paid.orderId(), paid.orderType());
            return;
        }
        log.info("ORDER_PAID_STOCK_CONFIRMED eventId={} orderId={} orderType={} reservations={}",
                event.eventId(), paid.orderId(), paid.orderType(), confirmed);
    }

    private EventEnvelope<OrderPaid> orderPaidOf(EventEnvelope<JsonNode> event) {
        OrderPaid paid;
        try {
            paid = mapper.convertValue(event.payload(), OrderPaid.class);
        } catch (IllegalArgumentException | JacksonException error) {
            throw new InvalidOrderEventException("Invalid OrderPaid payload in event " + event.eventId(), error);
        }
        if (paid == null) {
            throw new InvalidOrderEventException("OrderPaid event " + event.eventId() + " has no payload");
        }
        if (!paid.orderId().toString().equals(event.aggregateId())) {
            throw new InvalidOrderEventException("OrderPaid event " + event.eventId() + " has aggregateId "
                    + event.aggregateId() + " but orderId " + paid.orderId());
        }
        return new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), paid);
    }
}
