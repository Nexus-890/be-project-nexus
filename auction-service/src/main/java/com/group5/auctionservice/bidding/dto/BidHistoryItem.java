package com.group5.auctionservice.bidding.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record BidHistoryItem(BigDecimal amount, String bidderName, Instant placedAt) {
}
