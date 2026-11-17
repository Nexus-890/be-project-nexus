package com.group5.ratingservice.reputation.dto;

import com.group5.ratingservice.reputation.enums.TrustLevel;

import java.util.UUID;

public record ReputationSnapshot(UUID userId, int score, TrustLevel trustLevel, int totalTransaction,
        int successfulTransaction, int failedTransaction, long ratingCount, String policyVersion) {
}
