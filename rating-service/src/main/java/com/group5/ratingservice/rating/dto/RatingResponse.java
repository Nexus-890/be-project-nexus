package com.group5.ratingservice.rating.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.group5.ratingservice.entity.Rating;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record RatingResponse(UUID id, UUID fromUserId, UUID toUserId, UUID orderId,
                             short score, @JsonInclude(JsonInclude.Include.ALWAYS) String comment,
                             OffsetDateTime createdAt) {
    public static RatingResponse from(Rating rating) {
        return new RatingResponse(rating.getId(), rating.getFromUserId(), rating.getToUserId(),
                rating.getOrderId(), rating.getScore(), rating.getComment(), rating.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}
