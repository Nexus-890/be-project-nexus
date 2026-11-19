package com.group5.ratingservice.moderation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DisputeResponse(UUID id, UUID ratingId, UUID userId, String reason, DisputeStatus status,
        OffsetDateTime createdAt, OffsetDateTime resolvedAt, UUID resolvedBy, String resolutionReason,
        int ratingScore, String ratingComment) { }
