package com.group5.ratingservice.reputation.dto;

import com.group5.ratingservice.reputation.enums.TrustLevel;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReputationHistoryItem(UUID id, UUID operationId, UUID userId, UUID sourceId, Long sourceVersion,
        String changeType, int previousScore, int newScore, int delta, TrustLevel previousLevel,
        TrustLevel newLevel, UUID actorId, String reason, String policyVersion, OffsetDateTime changedAt) {
}
