package com.group5.ratingservice.rating;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

public record UserRatedPayload(UUID ratingId, UUID orderId,
                               @JsonInclude(JsonInclude.Include.ALWAYS) UUID auctionId,
                               UUID fromUserId, UUID toUserId, short score, int sourceVersion,
                               UUID actorId, Instant ratedAt) { }
