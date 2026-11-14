package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.InventoryStockUnavailable;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Validator;
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
public class InventoryStockUnavailableConsumer {
    public static final String CONSUMER = "commerce-inventory-stock-unavailable";

    private final IdempotentConsumer consumer;
    private final OrderService orders;
    private final Validator validator;
    private final JsonMapper mapper;
    private final Tracer tracer;

    @KafkaListener(topics = Topics.INVENTORY, groupId = "commerce-inventory-events",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        if (!"InventoryStockUnavailable".equals(event.eventType())) {
            return;
        }
        Span span = tracer.nextSpan().name("order-stock-unavailable").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            handle(new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                    event.traceId(), mapper.convertValue(event.payload(), InventoryStockUnavailable.class)));
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    public void handle(EventEnvelope<InventoryStockUnavailable> event) {
        InventoryStockUnavailable stock = event.payload();
        require(stock != null && validator.validate(stock).isEmpty(), "Invalid InventoryStockUnavailable payload");
        require(stock.orderId().toString().equals(event.aggregateId()), "Order and aggregate must match");
        consumer.handle(CONSUMER, event, ignored -> {
            boolean cancelled = orders.cancelPaidForMissingStock(stock.orderId());
            log.warn("Paid order has no stock: orderId={}, reservations={}, cancelled={}", stock.orderId(),
                    stock.reservations().stream().map(InventoryStockUnavailable.Line::reservationId).toList(),
                    cancelled);
        });
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
