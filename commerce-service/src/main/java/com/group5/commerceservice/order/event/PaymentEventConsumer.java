package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.service.RefundStatusHandler;
import com.group5.commerceservice.order.CheckoutPaymentConfirmedConsumer;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Một đầu nhận topic Payment, phân luồng confirmation và refund theo eventType. */
@Component
public class PaymentEventConsumer {
    private final RefundStatusHandler refunds;
    private final JsonMapper mapper;
    private final CheckoutPaymentConfirmedConsumer confirmations;

    public PaymentEventConsumer(RefundStatusHandler refunds, JsonMapper mapper,
                                CheckoutPaymentConfirmedConsumer confirmations) {
        this.refunds = refunds;
        this.mapper = mapper;
        this.confirmations = confirmations;
    }

    // Dùng chung một listener cho consumer group để các listener không tranh record và làm mất nhánh xử lý.
    @KafkaListener(topics = Topics.PAYMENT, groupId = "commerce-payment-events",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        switch (event.eventType()) {
            // Truyền Kafka key để consumer kiểm tra key là groupId, còn aggregate của envelope là sessionId.
            case "CheckoutPaymentConfirmed" -> confirmations.receive(record.key(), typed(event, CheckoutPaymentConfirmed.class));
            case "OrderRefundRequested" -> refunds.requested(typed(event, OrderRefundRequested.class));
            case "OrderRefundCompleted" -> refunds.completed(typed(event, OrderRefundCompleted.class));
            default -> {}
        }
    }

    /** Chuyển kiểu payload nhưng giữ nguyên eventId, thời gian và trace để chống lặp và truy vết. */
    private <T> EventEnvelope<T> typed(EventEnvelope<JsonNode> event, Class<T> payloadType) {
        return new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), mapper.convertValue(event.payload(), payloadType));
    }
}
