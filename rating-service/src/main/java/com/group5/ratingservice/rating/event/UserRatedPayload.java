package com.group5.ratingservice.rating.event;

import com.group5.ratingservice.rating.entity.Rating;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record UserRatedPayload(
        UUID ratingId,
        UUID orderId,
        UUID auctionId,
        UUID fromUserId,
        UUID toUserId,
        short score,
        long sourceVersion,
        UUID actorId,
        OffsetDateTime ratedAt
) {
    public static UserRatedPayload from(Rating rating) {
        return new UserRatedPayload(
                rating.getId(), rating.getOrderId(), rating.getAuctionId(), rating.getFromUserId(),
                rating.getToUserId(), rating.getScore(), 0, rating.getFromUserId(),
                rating.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}
