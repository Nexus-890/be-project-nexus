package com.group5.ratingservice.rating.dto;

import com.group5.ratingservice.rating.entity.Rating;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record RatingResponse(
        UUID id,
        UUID orderId,
        UUID auctionId,
        short score,
        String comment,
        String raterName,
        OffsetDateTime createdAt,
        boolean ratedByMe,
        String disputeStatus
) {
    public static RatingResponse from(Rating rating, UUID viewerId) {
        String name = rating.getRaterName();
        return new RatingResponse(
                rating.getId(), rating.getOrderId(), rating.getAuctionId(), rating.getScore(),
                rating.getComment(), name == null || name.isBlank() ? "***" : name,
                rating.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC),
                rating.getFromUserId().equals(viewerId), rating.getDisputeStatus());
    }
}
