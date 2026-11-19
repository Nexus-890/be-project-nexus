package com.group5.ratingservice.eligibility.dto;

import com.group5.ratingservice.eligibility.enums.EligibilityAction;

import com.group5.ratingservice.reputation.enums.TrustLevel;

import java.util.UUID;
import java.time.OffsetDateTime;

public record EligibilityResponse(
        UUID userId,
        EligibilityAction action,
        boolean eligible,
        int score,
        TrustLevel trustLevel,
        int minimumScore,
        String reason,
        String policyVersion,
        OffsetDateTime restrictedUntil
) {
}
