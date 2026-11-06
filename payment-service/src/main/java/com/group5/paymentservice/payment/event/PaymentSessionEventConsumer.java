package com.group5.paymentservice.payment.event;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.paymentservice.payment.event.payload.CheckoutPaymentRequested;
import com.group5.paymentservice.payment.event.payload.OrderCancelled;
import com.group5.paymentservice.payment.event.payload.OrderGroupCancelled;
import com.group5.paymentservice.payment.service.PaymentSessionEventHandler;
import com.group5.paymentservice.payment.service.RefundService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentSessionEventConsumer {
    private final PaymentSessionEventHandler handler;
    private final RefundService refunds;
    private final JsonMapper mapper;

    public PaymentSessionEventConsumer(PaymentSessionEventHandler handler, RefundService refunds, JsonMapper mapper) {
        this.handler = handler;
        this.refunds = refunds;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.COMMERCE,
            groupId = "${nexus.payment.consumer-group:payment-session-events}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        switch (event.eventType()) {
            case "CheckoutPaymentRequested" -> {
                requireGroupKey(record);
                handler.checkout(typed(event, CheckoutPaymentRequested.class));
            }
            case "OrderGroupCancelled" -> {
                requireGroupKey(record);
                handler.cancel(typed(event, OrderGroupCancelled.class));
            }
            case "OrderCancelled" -> refunds.cancelled(typed(event, OrderCancelled.class));
            default -> {}
        }
    }

    private void requireGroupKey(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        if (!record.value().aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Payment events must be keyed by orderGroupId");
        }
    }

    private <T> EventEnvelope<T> typed(EventEnvelope<JsonNode> event, Class<T> payloadType) {
        return new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), mapper.convertValue(event.payload(), payloadType));
    }
}
