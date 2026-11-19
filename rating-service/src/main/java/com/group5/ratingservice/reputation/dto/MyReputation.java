package com.group5.ratingservice.reputation.dto;

import com.group5.ratingservice.reputation.enums.TrustLevel;

import java.math.BigDecimal;

public record MyReputation(int score, TrustLevel trustLevel, int totalTransaction, int successfulTransaction,
        int failedTransaction, long ratingCount, BigDecimal averageRating, int successfulAuctions, int failedAuctions,
        long penaltyCount, String policyVersion) {
}
