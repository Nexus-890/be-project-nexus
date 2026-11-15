package com.group5.auctionservice.auction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.group5.auctionservice.auction.AuctionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuctionStateResponse(
        UUID auctionId,
        BigDecimal currentPrice,
        int bidCount,
        BigDecimal bidIncrement,
        @JsonInclude(JsonInclude.Include.ALWAYS) String leaderBidderName,
        Instant endAt,
        AuctionStatus status,
        Instant serverTime
) {
}
