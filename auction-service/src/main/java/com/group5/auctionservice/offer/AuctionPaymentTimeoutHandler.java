package com.group5.auctionservice.offer;

import com.group5.auctionservice.auction.*;
import com.group5.common_events.*;
import com.group5.common_outbox.*;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/** The timeout command never changes stock. Inventory independently consumes OrderCancelled. */
@Component
@RequiredArgsConstructor
public class AuctionPaymentTimeoutHandler {
    public static final String CONSUMER = "auction-payment-timeout";
    public static final String PAYMENT_DEADLINE_EXPIRED = "PAYMENT_DEADLINE_EXPIRED";
    public static final String BUYER_CANCELLED = "BUYER_CANCELLED";
    private final IdempotentConsumer idempotent;
    private final AuctionRepository auctions;
    private final AuctionStateMachine states;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Tracer tracer;

    @KafkaListener(topics = Topics.COMMERCE, groupId = CONSUMER,
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        var event = record.value();
        if (!"OrderCancelled".equals(event.eventType())
                || !"AUCTION".equals(event.payload().path("orderType").asString())
                || !(deadline(event.payload()) || buyerCancelled(event.payload()))) return;
        UUID order = uuid(event.payload(), "orderId");
        if (!order.toString().equals(event.aggregateId()) || !event.aggregateId().equals(record.key()))
            throw new IllegalArgumentException("Order identity mismatch");
        var span = tracer.nextSpan().name("auction.payment-timeout").start();
        try (var ignored = tracer.withSpan(span)) {
            idempotent.handle(CONSUMER, event, message -> apply(message.payload()));
        } finally { span.end(); }
    }

    @Transactional
    public void apply(JsonNode payload) {
        UUID order = uuid(payload, "orderId");
        UUID auctionId = uuid(payload, "referenceId");
        var auction = auctions.findForUpdateByIdAndDeletedAtIsNull(auctionId).orElse(null);
        if (auction == null || exists(order)) return;
        if (auction.getStatus() == AuctionStatus.WON && auction.getOrderId() == null) {
            // Different listener groups may observe cancellation before OrderCreated. Persist it,
            // then consume it in the settlement transaction instead of relying on endless retries.
            if (auction.getWinnerId().equals(uuid(payload, "buyerId"))) jdbc.update("""
                    INSERT INTO auction_pending_timeouts(order_id,payload) VALUES (?,?::jsonb)
                    ON CONFLICT DO NOTHING
                    """, order, payload.toString());
            return;
        }
        if (auction.getStatus() != AuctionStatus.SETTLED || !order.equals(auction.getOrderId())) return;
        if (!auction.getWinnerId().equals(uuid(payload, "buyerId"))
                || !auction.getSellerId().equals(uuid(payload, "sellerId")))
            throw new IllegalArgumentException("Timeout parties do not match the settled auction");
        boolean deadline = deadline(payload);
        if (!deadline && !buyerCancelled(payload)) return;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (deadline) {
            Instant cancelledAt = Instant.parse(payload.path("cancelledAt").asString());
            if (cancelledAt.isBefore(auction.getPaymentDueAt()))
                throw new IllegalArgumentException("Payment cancellation precedes the deadline");
            if (now.isBefore(auction.getPaymentDueAt())) throw new IllegalStateException("Payment deadline is still in the future");
        }
        states.transition(auction, AuctionStatus.PAYMENT_TIMEOUT);
        auctions.saveAndFlush(auction);
        jdbc.update("""
                INSERT INTO auction_payment_timeouts(order_id,auction_id,winner_id,payment_due_at,timed_out_at)
                VALUES (?,?,?,?,?)
                """, order, auctionId, auction.getWinnerId(), java.sql.Timestamp.from(auction.getPaymentDueAt()),
                java.sql.Timestamp.from(now));
        outbox.write("Auction", Topics.AUCTION, envelopes.create("AuctionPaymentTimeout", auctionId.toString(), Map.of(
                "auctionId", auctionId, "orderId", order, "winnerId", auction.getWinnerId(),
                "sellerId", auction.getSellerId(), "paymentDueAt", auction.getPaymentDueAt(), "timedOutAt", now,
                "reason", deadline ? PAYMENT_DEADLINE_EXPIRED : BUYER_CANCELLED, "auctionVersion", auction.getVersion())));
        jdbc.update("DELETE FROM auction_pending_timeouts WHERE order_id=?", order);
    }

    @Transactional
    public void applyPending(UUID orderId) {
        for (String payload : jdbc.queryForList("SELECT payload::text FROM auction_pending_timeouts WHERE order_id=?",
                String.class, orderId)) apply(JsonMapper.shared().readTree(payload));
    }

    private boolean exists(UUID order) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM auction_payment_timeouts WHERE order_id=?)", Boolean.class, order));
    }

    private static boolean deadline(JsonNode payload) {
        return PAYMENT_DEADLINE_EXPIRED.equals(payload.path("reason").asString());
    }

    private static boolean buyerCancelled(JsonNode payload) {
        String buyer = payload.path("buyerId").asString();
        return !buyer.isEmpty() && buyer.equals(payload.path("actorId").asString())
                && new java.math.BigDecimal(payload.path("paidAmount").asString("0")).signum() == 0;
    }

    static UUID uuid(JsonNode node, String name) { return UUID.fromString(node.path(name).asString()); }
}
