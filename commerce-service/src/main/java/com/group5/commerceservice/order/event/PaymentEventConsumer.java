package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.service.RefundStatusHandler;
import com.group5.commerceservice.order.CheckoutPaymentConfirmedConsumer;
import com.group5.commerceservice.order.PaymentUnreconciledConsumer;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
    private final PaymentUnreconciledConsumer unreconciled;
    private final Tracer tracer;

    public PaymentEventConsumer(RefundStatusHandler refunds, JsonMapper mapper,
                                CheckoutPaymentConfirmedConsumer confirmations,
                                PaymentUnreconciledConsumer unreconciled, Tracer tracer) {
        this.refunds = refunds;
        this.mapper = mapper;
        this.confirmations = confirmations;
        this.unreconciled = unreconciled;
        this.tracer = tracer;
    }

    // Dùng chung một listener cho consumer group để các listener không tranh record và làm mất nhánh xử lý.
    @KafkaListener(topics = Topics.PAYMENT, groupId = "commerce-payment-events",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = record.value();
        switch (event.eventType()) {
            // Truyền Kafka key để consumer kiểm tra key là groupId, còn aggregate của envelope là sessionId.
            case "CheckoutPaymentConfirmed" -> confirmations.receive(record.key(), typed(event, CheckoutPaymentConfirmed.class));
            case "PaymentUnreconciled" -> unreconciled.receive(record.key(), typed(event, PaymentUnreconciled.class));
            case "OrderRefundRequested" -> refunds.requested(typed(event, OrderRefundRequested.class));
            case "OrderRefundCompleted" -> completeRefund(typed(event, OrderRefundCompleted.class));
            default -> {}
        }
    }

    private void completeRefund(EventEnvelope<OrderRefundCompleted> event) {
        if (tracer.currentSpan() != null) {
            refunds.completed(event);
            return;
        }
        Span span = tracer.nextSpan().name("commerce-order-refund-completed").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            refunds.completed(event);
        } finally {
            span.end();
        }
    }

    /** Chuyển kiểu payload nhưng giữ nguyên eventId, thời gian và trace để chống lặp và truy vết. */
    private <T> EventEnvelope<T> typed(EventEnvelope<JsonNode> event, Class<T> payloadType) {
        return new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), mapper.convertValue(event.payload(), payloadType));
    }
}
