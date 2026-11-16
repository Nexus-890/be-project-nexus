package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.shared.config.AuctionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.Instant;

/** Gia hạn duy nhất cho bid đã được nhận, trong transaction đang giữ row lock của auction. */
@Component
@RequiredArgsConstructor
public class AntiSnipingPolicy {
    private final AuctionRepository auctions;
    private final AuctionProperties properties;

    /** Consumer dùng lúc nhận request để hàng đợi chậm không làm mất quyền gia hạn của bidder. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void extendAcceptedBid(UUID auctionId, UUID bidderId, Instant requestedAt) {
        if (properties.isAntiSnipingEnabled()) {
            auctions.extendQueuedEndAt(auctionId, bidderId, requestedAt,
                    properties.getAntiSnipingExtensionMinutes(), properties.getAntiSnipingMaxExtensionMinutes());
        }
    }

    /** Giữ hook SQL cũ cho kiểm tra biên trực tiếp và các caller đồng bộ đã tồn tại. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void extendAcceptedBid(UUID auctionId, UUID bidderId) {
        if (properties.isAntiSnipingEnabled()) {
            // SQL dùng cùng DB now() với acceptBid, đồng thời kiểm tra window và cap.
            auctions.extendEndAt(auctionId, bidderId, properties.getAntiSnipingExtensionMinutes(),
                    properties.getAntiSnipingMaxExtensionMinutes());
        }
    }
}
