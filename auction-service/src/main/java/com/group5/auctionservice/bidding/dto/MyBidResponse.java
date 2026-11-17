package com.group5.auctionservice.bidding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.group5.auctionservice.auction.AuctionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MyBidResponse(UUID auctionId, String title, BigDecimal amount, Instant placedAt,
                            AuctionStatus auctionStatus, boolean leading, boolean won,
                            @JsonInclude(JsonInclude.Include.ALWAYS) UUID orderId,
                            @JsonInclude(JsonInclude.Include.ALWAYS) Instant paymentDueAt,
                            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal finalPrice) { }
