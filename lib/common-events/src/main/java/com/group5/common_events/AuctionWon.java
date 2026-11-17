package com.group5.common_events;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Shared #170/#172 settlement contract; money remains a decimal string on the wire. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record AuctionWon(UUID auctionId, UUID sellerId, UUID productId, UUID skuId, UUID winnerId,
                         String finalPrice, String currency, String title, String imageUrl,
                         int quantity, Instant endAt, Instant paymentDueAt,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         UUID offerId) {
    public AuctionWon(UUID auctionId, UUID sellerId, UUID productId, UUID skuId, UUID winnerId,
                      String finalPrice, String currency, String title, String imageUrl,
                      int quantity, Instant endAt, Instant paymentDueAt) {
        this(auctionId, sellerId, productId, skuId, winnerId, finalPrice, currency, title, imageUrl,
                quantity, endAt, paymentDueAt, null);
    }
    public AuctionWon {
        Objects.requireNonNull(auctionId); Objects.requireNonNull(sellerId);
        Objects.requireNonNull(productId); Objects.requireNonNull(skuId); Objects.requireNonNull(winnerId);
        Objects.requireNonNull(endAt); Objects.requireNonNull(paymentDueAt);
        if (finalPrice == null || !finalPrice.matches("\\d+\\.\\d{2}")
                || new BigDecimal(finalPrice).precision() > 19 || new BigDecimal(finalPrice).signum() <= 0
                || !"VND".equals(currency) || quantity != 1 || sellerId.equals(winnerId)
                || title == null || title.isBlank() || title.length() > 255
                || imageUrl == null || imageUrl.isBlank() || imageUrl.length() > 500
                || !URI.create(imageUrl).isAbsolute() || !paymentDueAt.isAfter(endAt)) {
            throw new IllegalArgumentException("Invalid AuctionWon snapshot");
        }
    }
}
