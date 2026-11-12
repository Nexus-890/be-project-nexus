package com.group5.auctionservice.auction;

import java.time.Instant;
import java.util.UUID;

public record AuctionCancelled(UUID auctionId, UUID sellerId, UUID productId, UUID skuId,
                               UUID actorId, String cancelledBy, String reason,
                               long auctionVersion, Instant cancelledAt) {
    static AuctionCancelled from(Auction auction) {
        return new AuctionCancelled(auction.getId(), auction.getSellerId(), auction.getProductId(),
                auction.getSkuId(), auction.getCancelledActorId(), auction.getCancelledBy(),
                auction.getCancelledReason(), auction.getVersion(), auction.getCancelledAt());
    }
}
