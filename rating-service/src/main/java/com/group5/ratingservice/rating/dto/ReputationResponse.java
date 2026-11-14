package com.group5.ratingservice.rating.dto;

import com.group5.ratingservice.entity.UserReputation;
import com.group5.ratingservice.enums.TrustLevel;
import java.util.UUID;

public record ReputationResponse(UUID userId, short score, TrustLevel trustLevel,
                                 int totalTransaction, int successfulTransaction, int failedTransaction) {
    public static ReputationResponse from(UserReputation reputation) {
        return new ReputationResponse(reputation.getUserId(), reputation.getScore(), reputation.getTrustLevel(),
                reputation.getTotalTransaction(), reputation.getSuccessfulTransaction(), reputation.getFailedTransaction());
    }
}
