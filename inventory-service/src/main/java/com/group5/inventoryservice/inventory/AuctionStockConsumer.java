package com.group5.inventoryservice.inventory;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.inventoryservice.reservation.service.ReservationService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class AuctionStockConsumer {
    private static final String CONSUMER = "inventory-auction-stock";

    private final IdempotentConsumer idempotent;
    private final ReservationService reservations;
    private final Tracer tracer;
    private final com.group5.inventoryservice.reservation.service.OfferReservationService offerReservations;

    @KafkaListener(id = CONSUMER, topics = Topics.AUCTION,
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(EventEnvelope<JsonNode> event) {
        switch (event.eventType()) {
            case "AuctionCreated" -> handle(event, this::hold);
            case "AuctionWon" -> handle(event, message -> {
                if(hasOffer(message)) offerReservations.winner(auctionId(message),uuid(message.payload(),"offerId"),uuid(message.payload(),"winnerId"));
                else reservations.recordAuctionWinner(auctionId(message), uuid(message.payload(), "winnerId"));
            });
            case "SecondChanceOffered" -> handle(event,message->offerReservations.hold(auctionId(message),uuid(message.payload(),"offerId"),
                    uuid(message.payload(),"bidderId"),uuid(message.payload(),"skuId"),Instant.parse(message.payload().path("expiresAt").asString())));
            case "SecondChanceDeclined", "SecondChanceExpired" -> handle(event,message->offerReservations.release(auctionId(message),
                    uuid(message.payload(),"offerId"),"SecondChanceDeclined".equals(message.eventType())?"DECLINED":"EXPIRED"));
            case "AuctionFailed" -> handle(event,
                    message -> reservations.releaseAuction(auctionId(message), "AUCTION_FAILED"));
            case "AuctionCancelled" -> handle(event,
                    message -> reservations.releaseAuction(auctionId(message), "AUCTION_CANCELLED"));
            case "AuctionSettled" -> handle(event, message -> {
                if(hasOffer(message)) offerReservations.link(auctionId(message),uuid(message.payload(),"offerId"),uuid(message.payload(),"orderId"));
                else reservations.linkAuctionOrder(auctionId(message), uuid(message.payload(), "orderId"));
            });
            default -> {
            }
        }
    }

    private void handle(EventEnvelope<JsonNode> event, Consumer<EventEnvelope<JsonNode>> handler) {
        Span span = tracer.nextSpan().name("inventory.auction-stock").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            idempotent.handle(CONSUMER, event, handler);
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private static boolean hasOffer(EventEnvelope<JsonNode> event) {
        return event.payload().hasNonNull("offerId");
    }

    private void hold(EventEnvelope<JsonNode> event) {
        JsonNode payload = event.payload();
        if (payload.path("quantity").asInt() != 1) {
            throw new IllegalArgumentException("AuctionCreated must hold exactly one unit");
        }
        reservations.holdAuction(auctionId(event), uuid(payload, "skuId"),
                Instant.parse(payload.path("endAt").asText()));
    }

    private static UUID auctionId(EventEnvelope<JsonNode> event) {
        UUID auctionId = uuid(event.payload(), "auctionId");
        if (!auctionId.toString().equals(event.aggregateId())) {
            throw new IllegalArgumentException("Auction event aggregateId must match auctionId");
        }
        return auctionId;
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }
}
