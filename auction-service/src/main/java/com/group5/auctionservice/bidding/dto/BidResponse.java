package com.group5.auctionservice.bidding.dto;

import java.time.Instant;
import java.util.UUID;

/** Biên nhận bất biến của POST; kết quả xử lý được đọc ở endpoint trạng thái bid. */
public record BidResponse(UUID bidId, String status, Instant requestedAt) {}
