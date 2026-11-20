package com.group5.ratingservice.reputation.dto;

import com.group5.ratingservice.reputation.enums.TrustLevel;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PenaltyItem(UUID id, String changeType, UUID sourceId, int delta, int previousScore, int newScore,
        TrustLevel previousLevel, TrustLevel newLevel, String reason, OffsetDateTime changedAt) {
}
