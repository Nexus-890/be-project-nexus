package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.service.RefundStatusHandler;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentEventConsumer {
    private final RefundStatusHandler refunds;
    private final JsonMapper mapper;

    public PaymentEventConsumer(RefundStatusHandler refunds, JsonMapper mapper) {
        this.refunds = refunds;
        this.mapper = mapper;
    }

    // CONSUMER KAFKA ĐẦU TIÊN CỦA COMMERCE: CHỈ NHẬN EVENT REFUND, EVENT KHÁC TRÊN TOPIC PAYMENT BỎ QUA
    @KafkaListener(topics = Topics.PAYMENT, groupId = "commerce-payment-events",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        switch (event.eventType()) {
            case "OrderRefundRequested" -> refunds.requested(typed(event, OrderRefundRequested.class));
            case "OrderRefundCompleted" -> refunds.completed(typed(event, OrderRefundCompleted.class));
            default -> {}
        }
    }

    private <T> EventEnvelope<T> typed(EventEnvelope<JsonNode> event, Class<T> payloadType) {
        return new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), mapper.convertValue(event.payload(), payloadType));
    }
}
