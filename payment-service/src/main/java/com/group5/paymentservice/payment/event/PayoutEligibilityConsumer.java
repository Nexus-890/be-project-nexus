package com.group5.paymentservice.payment.event;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class PayoutEligibilityConsumer {
    private final PayoutEligibilityService payouts;
    private final JsonMapper mapper;

    @KafkaListener(topics = Topics.COMMERCE, groupId = "payment-payout-eligibility",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        var event = record.value();
        if (!"OrderCompleted".equals(event.eventType())) return;
        if (!event.aggregateId().equals(record.key())) throw new IllegalArgumentException("Completion key must be order ID");
        payouts.completed(new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), mapper.convertValue(event.payload(), OrderCompleted.class)));
    }
}
