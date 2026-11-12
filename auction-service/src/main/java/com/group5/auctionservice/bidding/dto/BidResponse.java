package com.group5.auctionservice.bidding.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BidResponse(
        UUID bidId,
        BigDecimal currentPrice,
        UUID leaderBidderId
) {
}
