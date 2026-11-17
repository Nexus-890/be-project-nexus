package com.group5.auctionservice.auction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuctionCancelled(UUID auctionId, UUID sellerId, UUID productId, UUID skuId,
                               UUID actorId, String cancelledBy, String reason,
                               long auctionVersion, Instant cancelledAt, List<UUID> bidderIds) {
    static AuctionCancelled from(Auction auction, List<UUID> bidderIds) {
        // Snapshot người nhận trong chính transaction hủy; không yêu cầu Notification đọc DB đấu giá.
        return new AuctionCancelled(auction.getId(), auction.getSellerId(), auction.getProductId(),
                auction.getSkuId(), auction.getCancelledActorId(), auction.getCancelledBy(),
                auction.getCancelledReason(), auction.getVersion(), auction.getCancelledAt(), List.copyOf(bidderIds));
    }
}
