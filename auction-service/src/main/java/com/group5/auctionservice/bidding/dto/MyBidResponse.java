package com.group5.auctionservice.bidding.dto;

import com.group5.auctionservice.auction.AuctionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MyBidResponse(UUID auctionId, String title, BigDecimal amount, Instant placedAt,
                            AuctionStatus auctionStatus, boolean leading, boolean won) { }
