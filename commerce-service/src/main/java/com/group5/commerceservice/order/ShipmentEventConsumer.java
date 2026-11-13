package com.group5.commerceservice.order;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
    private final Tracer tracer;

    @KafkaListener(topics = Topics.SHIPMENT, groupId = "commerce-shipment-events",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        if (!ShipmentStatusHandler.supports(event.eventType())
                || ("ShipmentStatusUpdated".equals(event.eventType())
                    && java.util.Set.of("PENDING_TRACKING", "CREATED", "PICKING", "PACKED", "READY_TO_SHIP")
                        .contains(event.payload().path("status").asString()))) {
            log.debug("Shipment event has no order transition: eventId={}, eventType={}",
                    event.eventId(), event.eventType());
            return;
        }
        ShipmentStatusChanged payload = mapper.convertValue(event.payload(), ShipmentStatusChanged.class);
        if (payload.orderId() == null || !payload.orderId().toString().equals(record.key())) {
            throw new IllegalArgumentException("Shipment event key must be its orderId");
        }
        // Kafka publish tay có thể không có observation; outbox vẫn cần trace đang hoạt động.
        Span span = tracer.nextSpan().name("order-shipment-event").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            shipments.handle(new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(),
                    event.occurredAt(), event.traceId(), payload));
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }
}
