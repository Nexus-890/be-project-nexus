package com.group5.auctionservice.auction;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuctionStockRejectedConsumer {
    private static final String CONSUMER = "auction-stock-rejection";

    private final IdempotentConsumer idempotent;
    private final AuctionService auctions;
    private final Tracer tracer;

    @KafkaListener(id = CONSUMER, topics = Topics.INVENTORY,
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(EventEnvelope<JsonNode> event) {
        if (!"AuctionStockRejected".equals(event.eventType())) {
            return;
        }
        JsonNode payload = event.payload();
        UUID auctionId = UUID.fromString(payload.required("auctionId").asString());
        UUID skuId = UUID.fromString(payload.required("skuId").asString());
        if (!auctionId.toString().equals(event.aggregateId()) ||
                !"OUT_OF_STOCK".equals(payload.required("reason").asString())) {
            throw new IllegalArgumentException("Invalid AuctionStockRejected event");
        }
        Span span = tracer.nextSpan().name("auction.stock-rejected").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            idempotent.handle(CONSUMER, event, message -> auctions.cancelForUnavailableStock(auctionId, skuId));
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }
}
