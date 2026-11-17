package com.group5.ratingservice.eligibility.event;

import com.group5.ratingservice.eligibility.service.RatingEligibilityService;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCompletedConsumer {

    public static final String CONSUMER = "rating-service.order-completed";

    private final IdempotentConsumer idempotentConsumer;
    private final RatingEligibilityService eligibilityService;

    @KafkaListener(
            topics = Topics.COMMERCE,
            groupId = "${nexus.rating.group-id:nexus.rating.order-completed.v1}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY
    )
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> envelope = record.value();
        if (envelope == null) {
            throw new IllegalArgumentException("OrderCompleted envelope must not be null");
        }
        if (!"OrderCompleted".equals(envelope.eventType())) {
            return;
        }
        if (!Topics.COMMERCE.equals(record.topic())) {
            throw new IllegalArgumentException("OrderCompleted must be received on " + Topics.COMMERCE);
        }
        OrderCompletedPayload completion = OrderCompletedPayload.from(envelope.payload());
        String orderId = completion.orderId().toString();
        if (!orderId.equals(envelope.aggregateId()) || !orderId.equals(record.key())) {
            throw new IllegalArgumentException("OrderCompleted aggregateId and Kafka key must equal orderId");
        }

        boolean handled = idempotentConsumer.handle(CONSUMER, envelope,
                event -> eligibilityService.recordCompletion(event.eventId(), completion));
        if (handled) {
            log.info("RATING_ELIGIBILITY_RECORDED eventId={} orderId={}", envelope.eventId(), orderId);
        }
    }
}
