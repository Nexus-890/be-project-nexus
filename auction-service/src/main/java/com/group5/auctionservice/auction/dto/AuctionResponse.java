package com.group5.auctionservice.auction.dto;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuctionResponse(
        UUID id,
        UUID productId,
        UUID skuId,
        UUID sellerId,
        String title,
        String imageUrl,
        BigDecimal startingPrice,
        BigDecimal bidIncrement,
        BigDecimal currentPrice,
        int bidCount,
        String currency,
        Instant startAt,
        Instant endAt,
        Instant originalEndAt,
        short extensionCount,
        AuctionStatus status,
        String cancelledBy,
        String cancelledReason,
        UUID cancelledActorId,
        Instant cancelledAt
) {
    public static AuctionResponse from(Auction auction) {
        return new AuctionResponse(
                auction.getId(), auction.getProductId(), auction.getSkuId(), auction.getSellerId(),
                auction.getTitle(), auction.getImageUrl(), auction.getStartingPrice(), auction.getBidIncrement(),
                auction.getCurrentPrice(), auction.getBidCount(), auction.getCurrency(), auction.getStartAt(),
                auction.getEndAt(), auction.getOriginalEndAt(), auction.getExtensionCount(), auction.getStatus(),
                auction.getCancelledBy(), auction.getCancelledReason(), auction.getCancelledActorId(), auction.getCancelledAt());
    }
}
