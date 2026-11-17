package com.group5.catalogservice.product.event;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProductFlagsConsumer {
    private static final String CONSUMER = "catalog-product-flags";

    private final IdempotentConsumer idempotent;
    private final NamedParameterJdbcTemplate jdbc;

    @KafkaListener(id = CONSUMER, topics = {Topics.COMMERCE, Topics.AUCTION},
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(EventEnvelope<JsonNode> event) {
        switch (event.eventType()) {
            case "OrderCreated" -> idempotent.handle(CONSUMER, event, message -> markOrdered(message.payload()));
            case "AuctionCreated" -> idempotent.handle(CONSUMER, event, message -> updateAuction(message, true));
            case "AuctionEnded", "AuctionCancelled" ->
                    idempotent.handle(CONSUMER, event, message -> updateAuction(message, false));
            default -> {
            }
        }
    }

    private void markOrdered(JsonNode payload) {
        JsonNode ids = payload.required("productIds");
        if (!ids.isArray() || ids.isEmpty()) {
            throw new IllegalArgumentException("OrderCreated requires productIds");
        }
        Set<UUID> products = new HashSet<>();
        ids.forEach(id -> products.add(UUID.fromString(id.asString())));
        jdbc.update("""
                WITH locked AS MATERIALIZED (
                    SELECT id FROM products WHERE id IN (:ids) AND NOT has_order
                    ORDER BY id FOR UPDATE
                )
                UPDATE products p SET has_order = true FROM locked WHERE p.id = locked.id
                """, Map.of("ids", products));
    }

    private void updateAuction(EventEnvelope<JsonNode> event, boolean open) {
        UUID id = UUID.fromString(event.payload().required("auctionId").asString());
        UUID product = UUID.fromString(event.payload().required("productId").asString());
        if (!id.toString().equals(event.aggregateId())) {
            throw new IllegalArgumentException("Auction event aggregateId must match auctionId");
        }
        var parameters = Map.of("product", product, "auction", id, "open", open);
        if (jdbc.queryForList("SELECT id FROM products WHERE id = :product FOR UPDATE",
                parameters, UUID.class).isEmpty()) {
            return;
        }

        // A terminal event is also a marker when it arrives before AuctionCreated.
        int affected = jdbc.update("""
                INSERT INTO catalog_auction_flags (auction_id, product_id, is_open)
                VALUES (:auction, :product, :open)
                ON CONFLICT (auction_id) DO UPDATE SET is_open = false
                WHERE catalog_auction_flags.product_id = :product
                  AND catalog_auction_flags.is_open AND NOT :open
                """, parameters);
        if (affected == 0) {
            return;
        }
        jdbc.update("""
                UPDATE products p SET in_auction = open_auctions.present
                FROM (SELECT EXISTS (SELECT 1 FROM catalog_auction_flags
                                     WHERE product_id = :product AND is_open) AS present) open_auctions
                WHERE p.id = :product AND p.in_auction IS DISTINCT FROM open_auctions.present
                """, parameters);
    }
}
