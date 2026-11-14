package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.PaymentUnreconciled;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentUnreconciledConsumer {
    public static final String CONSUMER = "commerce-payment-unreconciled";

    private final IdempotentConsumer consumer;
    private final OrderRepository orders;
    private final Validator validator;

    public void receive(String key, EventEnvelope<PaymentUnreconciled> event) {
        require(event != null && event.payload() != null, "Missing PaymentUnreconciled payload");
        PaymentUnreconciled payment = event.payload();
        require(validator.validate(payment).isEmpty(), "Invalid PaymentUnreconciled payload");
        require(payment.sessionId().toString().equals(event.aggregateId()), "Session and aggregate must match");
        require(payment.orderGroupId().toString().equals(key), "Group and Kafka key must match");
        consumer.handle(CONSUMER, event, ignored -> {
            int noted = orders.recordPaymentNote(payment.orderGroupId(), payment.affectedOrderIds(), payment.reason());
            log.warn("Payment needs reconciliation: sessionId={}, orderGroupId={}, transactionId={}, reasonCode={}, "
                            + "ordersNoted={}", payment.sessionId(), payment.orderGroupId(), payment.transactionId(),
                    payment.reasonCode(), noted);
        });
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
