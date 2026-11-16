package com.group5.auctionservice.bidding;

import java.time.Instant;
import java.util.UUID;

/** Chỉ định danh bản ghi bền vững; consumer luôn đọc giá/người bid từ DB do service sở hữu. */
public record BidCommand(UUID auctionId, UUID bidId, Instant requestedAt) {}
