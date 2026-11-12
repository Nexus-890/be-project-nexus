package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.shared.config.AuctionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Gia hạn duy nhất cho bid đã được nhận, trong transaction đang giữ row lock của auction. */
@Component
@RequiredArgsConstructor
public class AntiSnipingPolicy {
    private final AuctionRepository auctions;
    private final AuctionProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public void extendAcceptedBid(UUID auctionId, UUID bidderId) {
        if (properties.isAntiSnipingEnabled()) {
            // SQL dùng cùng DB now() với acceptBid, đồng thời kiểm tra window và cap.
            auctions.extendEndAt(auctionId, bidderId, properties.getAntiSnipingExtensionMinutes(),
                    properties.getAntiSnipingMaxExtensionMinutes());
        }
    }
}
