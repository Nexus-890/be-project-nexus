package com.group5.auctionservice.stream;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexus.auction.stream.enabled", havingValue = "true", matchIfMissing = true)
public class AuctionStreamConsumer {
    private static final Set<String> STATE_EVENTS = Set.of("AuctionStarted", "BidPlaced", "AuctionEnded",
            "AuctionWon", "AuctionFailed", "AuctionCancelled", "AuctionPaymentTimeout", "AuctionSettled");
    private final AuctionStreamRegistry streams;

    /** Mỗi instance dùng group riêng để mọi viewer đều nhận sự kiện, kể cả khi có nhiều replica. */
    @KafkaListener(id = "auction-stream", groupId = "${nexus.auction.stream.group-id:auction-stream-${random.uuid}}",
            topics = Topics.AUCTION, containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY,
            properties = "auto.offset.reset=latest")
    public void receive(EventEnvelope<JsonNode> event) {
        if (!STATE_EVENTS.contains(event.eventType())) {
            return;
        }
        UUID auctionId = UUID.fromString(event.aggregateId());
        if (event.payload().hasNonNull("auctionId")
                && !auctionId.toString().equals(event.payload().required("auctionId").asString())) {
            throw new IllegalArgumentException("Auction event key and payload do not match");
        }
        // Không dùng processed_events: mỗi instance phải broadcast cùng event; version loại bản gửi trùng.
        streams.changed(auctionId);
    }
}