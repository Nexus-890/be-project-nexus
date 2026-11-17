package com.group5.auctionservice.settlement;

import com.group5.auctionservice.auction.*;
import com.group5.common_events.*;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** SETTLED xác nhận đơn đã tồn tại; không xác nhận thanh toán hay trừ tồn kho. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementStatusUpdater {
    public static final String CONSUMER = "auction-order-created-settlement";
    private final IdempotentConsumer consumer;
    private final AuctionRepository auctions;
    private final AuctionStateMachine states;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final JsonMapper mapper;
    private final Tracer tracer;

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Created(UUID orderId, String orderType, UUID referenceId) { }
    public record Settled(UUID auctionId, UUID orderId, Instant settledAt) { }

    @KafkaListener(topics = Topics.COMMERCE, groupId = CONSUMER,
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        var event = record.value();
        if (!"OrderCreated".equals(event.eventType())) return;
        // STANDARD không tham gia settlement, kể cả referenceId rỗng.
        if (!"AUCTION".equals(event.payload().path("orderType").asString())) return;
        var created = mapper.convertValue(event.payload(), Created.class);
        if (created.orderId() == null || created.referenceId() == null
                || !created.orderId().toString().equals(event.aggregateId())
                || !event.aggregateId().equals(record.key())) throw new IllegalArgumentException("Order identity mismatch");
        var span = tracer.nextSpan().name("auction-order-settle").start();
        try (var scope = tracer.withSpan(span)) {
            consumer.handle(CONSUMER, event, ignored -> apply(created));
        } finally { span.end(); }
    }

    private void apply(Created created) {
        Auction auction = auctions.findForUpdateByIdAndDeletedAtIsNull(created.referenceId()).orElse(null);
        if (auction == null || auction.getStatus() != AuctionStatus.WON) {
            log.warn("Settlement ignored: auctionId={}, status={}", created.referenceId(),
                    auction == null ? "MISSING" : auction.getStatus());
            return;
        }
        states.transition(auction, AuctionStatus.SETTLED);
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        auction.recordSettlement(created.orderId(), now);
        auctions.saveAndFlush(auction);
        outbox.write("Auction", Topics.AUCTION, envelopes.create("AuctionSettled", auction.getId().toString(),
                new Settled(auction.getId(), created.orderId(), now)));
    }
}
