package com.group5.auctionservice.bidding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Snapshot kết quả của riêng yêu cầu, không lấy nhầm giá của bid được xử lý sau. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BidStatusResponse(
        UUID bidId,
        String status,
        Instant requestedAt,
        BigDecimal amount,
        BigDecimal currentPrice,
        UUID leaderBidderId,
        String reason,
        BigDecimal minimum) {}
