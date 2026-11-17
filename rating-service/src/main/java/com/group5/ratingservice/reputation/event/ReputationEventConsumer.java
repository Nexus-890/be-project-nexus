package com.group5.ratingservice.reputation.event;

import com.group5.ratingservice.reputation.service.ReputationService;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.ratingservice.eligibility.event.OrderCompletedPayload;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class ReputationEventConsumer {
    public static final String COMPLETION = "rating-service.reputation.order-completed";
    public static final String RATING = "rating-service.reputation.user-rated";
    public static final String TIMEOUT = "rating-service.reputation.auction-timeout";
    private final IdempotentConsumer idempotency;
    private final ReputationService reputations;
    private final Tracer tracer;

    @KafkaListener(topics = Topics.COMMERCE, groupId = "${nexus.reputation.group-id:nexus.rating.reputation.v1}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY, properties = "auto.offset.reset=earliest")
    public void commerce(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = required(record);
        if (!"OrderCompleted".equals(event.eventType())) return;
        var source = OrderCompletedPayload.from(event.payload());
        verify(record, Topics.COMMERCE, source.orderId().toString(), source.orderId().toString());
        var completion = new ReputationInputs.Completion(source.orderId(), source.buyerId(), source.sellerId(),
                source.auctionId(), source.completedAt(), source.orderVersion());
        handle(COMPLETION, event, envelope -> reputations.complete(completion, envelope.eventId()));
    }

    @KafkaListener(topics = Topics.RATING, groupId = "${nexus.reputation.group-id:nexus.rating.reputation.v1}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY, properties = "auto.offset.reset=earliest")
    public void rating(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = required(record);
        if (!"UserRated".equals(event.eventType())) return;
        var input = ReputationInputs.RatingInput.from(event.payload());
        verify(record, Topics.RATING, input.ratingId().toString(), input.toUserId().toString());
        handle(RATING, event, envelope -> reputations.rate(input, envelope.eventId()));
    }

    @KafkaListener(topics = Topics.AUCTION, groupId = "${nexus.reputation.group-id:nexus.rating.reputation.v1}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY, properties = "auto.offset.reset=earliest")
    public void auction(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> event = required(record);
        if (!"AuctionPaymentTimeout".equals(event.eventType())) return;
        var input = ReputationInputs.Timeout.from(event.payload());
        verify(record, Topics.AUCTION, input.auctionId().toString(), input.auctionId().toString());
        handle(TIMEOUT, event, envelope -> reputations.timeout(input, envelope.eventId()));
    }

    private void handle(String consumer, EventEnvelope<JsonNode> event, Consumer<EventEnvelope<JsonNode>> action) {
        Span span = tracer.nextSpan().name(consumer).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            idempotency.handle(consumer, event, action);
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private static EventEnvelope<JsonNode> required(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        if (record.value() == null) throw new IllegalArgumentException("A reputation input envelope is required");
        return record.value();
    }

    private static void verify(ConsumerRecord<String, EventEnvelope<JsonNode>> record, String topic, String aggregate, String key) {
        if (!topic.equals(record.topic()) || !aggregate.equals(record.value().aggregateId()) || !key.equals(record.key())) {
            throw new IllegalArgumentException("Reputation input topic, aggregateId or Kafka key does not match its contract");
        }
    }
}
