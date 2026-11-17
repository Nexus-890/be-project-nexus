package com.group5.commerceservice.order;

import com.group5.common_events.*;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class AuctionWonConsumer {
    public static final String CONSUMER = "commerce-auction-won";
    private final IdempotentConsumer consumer;
    private final AuctionOrderService orders;
    private final JsonMapper mapper;
    private final Tracer tracer;

    @KafkaListener(topics = Topics.AUCTION, groupId = CONSUMER,
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        var event = record.value();
        if (!"AuctionWon".equals(event.eventType())) return;
        var won = mapper.convertValue(event.payload(), AuctionWon.class);
        if (!won.auctionId().toString().equals(event.aggregateId())
                || !event.aggregateId().equals(record.key())) throw new IllegalArgumentException("Auction identity mismatch");
        var typed = new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(),
                event.occurredAt(), event.traceId(), won);
        var span = tracer.nextSpan().name("auction-order-create").start();
        try (var scope = tracer.withSpan(span)) {
            consumer.handle(CONSUMER, typed, envelope -> orders.create(envelope.payload()));
        } finally { span.end(); }
    }
}
