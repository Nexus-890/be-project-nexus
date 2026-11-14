package com.group5.inventoryservice.inventory;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.inventoryservice.reservation.service.ReservationService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    public static final String LISTENER_ID = "inventory-order-events";
    static final String ORDER_PAID_CONSUMER = "inventory-order-paid";
    static final String ORDER_CANCELLED_CONSUMER = "inventory-order-cancelled";

    private final IdempotentConsumer idempotent;
    private final ReservationService reservations;
    private final JsonMapper mapper;
    private final Tracer tracer;

    @KafkaListener(id = LISTENER_ID, topics = Topics.COMMERCE,
            groupId = "${nexus.inventory.consumer-group:inventory-order-events}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        switch (event.eventType()) {
            case "OrderPaid" -> traced("inventory-order-paid", () -> idempotent.handle(ORDER_PAID_CONSUMER,
                    payloadOf(event, OrderPaid.class, OrderPaid::orderId), this::orderPaid));
            case "OrderCancelled" -> idempotent.handle(ORDER_CANCELLED_CONSUMER,
                    payloadOf(event, OrderCancelled.class, OrderCancelled::orderId), this::orderCancelled);
            default -> {
            }
        }
    }

    private void traced(String name, Runnable work) {
        Span span = tracer.nextSpan().name(name).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            work.run();
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private void orderPaid(EventEnvelope<OrderPaid> event) {
        OrderPaid paid = event.payload();
        ReservationService.OrderConfirmation result = reservations.confirmOrder(paid.orderId());
        if (result.reservations() == 0) {
            log.info("ORDER_PAID_NO_RESERVATION eventId={} orderId={} orderType={}",
                    event.eventId(), paid.orderId(), paid.orderType());
            return;
        }
        if (!result.reclaimed().isEmpty()) {
            log.warn("ORDER_PAID_EXPIRED_STOCK_RECLAIMED eventId={} orderId={} reservations={}",
                    event.eventId(), paid.orderId(), result.reclaimed());
        }
        if (!result.lost().isEmpty()) {
            log.warn("ORDER_PAID_STOCK_UNAVAILABLE eventId={} orderId={} orderType={} missing={} "
                            + "published=InventoryStockUnavailable",
                    event.eventId(), paid.orderId(), paid.orderType(), result.lost());
            return;
        }
        log.info("ORDER_PAID_STOCK_CONFIRMED eventId={} orderId={} orderType={} reservations={}",
                event.eventId(), paid.orderId(), paid.orderType(), result.reservations());
    }

    private void orderCancelled(EventEnvelope<OrderCancelled> event) {
        OrderCancelled cancelled = event.payload();
        ReservationService.OrderRelease result = reservations.releaseOrder(cancelled.orderId());
        log.info("ORDER_CANCELLED_STOCK_RELEASED eventId={} orderId={} orderType={} reason={} released={} returned={}",
                event.eventId(), cancelled.orderId(), cancelled.orderType(), cancelled.reason(),
                result.released(), result.returned());
    }

    private <T> EventEnvelope<T> payloadOf(EventEnvelope<JsonNode> event, Class<T> type, Function<T, UUID> orderId) {
        String name = type.getSimpleName();
        T payload;
        try {
            payload = mapper.convertValue(event.payload(), type);
        } catch (IllegalArgumentException | JacksonException error) {
            throw new InvalidOrderEventException("Invalid " + name + " payload in event " + event.eventId(), error);
        }
        if (payload == null) {
            throw new InvalidOrderEventException(name + " event " + event.eventId() + " has no payload");
        }
        if (!orderId.apply(payload).toString().equals(event.aggregateId())) {
            throw new InvalidOrderEventException(name + " event " + event.eventId() + " has aggregateId "
                    + event.aggregateId() + " but orderId " + orderId.apply(payload));
        }
        return new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), payload);
    }
}
