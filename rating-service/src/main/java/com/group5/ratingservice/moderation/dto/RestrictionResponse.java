package com.group5.ratingservice.moderation.dto;

import com.group5.ratingservice.eligibility.enums.EligibilityAction;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RestrictionResponse(UUID id, UUID userId, EligibilityAction action, String reason,
        OffsetDateTime restrictedUntil, OffsetDateTime createdAt, UUID createdBy, OffsetDateTime revokedAt,
        UUID revokedBy, String revocationReason, boolean active) { }
