package com.group5.ratingservice.reputation.model;

import com.group5.ratingservice.reputation.dto.ReputationSnapshot;
import com.group5.ratingservice.reputation.enums.TrustLevel;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public final class ReputationState {
    private UUID userId;
    private int score;
    private TrustLevel trustLevel;
    private int totalTransaction;
    private int successfulTransaction;
    private int failedTransaction;
    private long ratingCount;
    private long ratingScoreSum;
    private long penaltyPoints;
    private int successfulAuctionCount;
    private int failedAuctionCount;
    private BigDecimal adjustmentOffset;
    private String policyVersion;

    public ReputationSnapshot snapshot(String fallbackVersion) {
        return new ReputationSnapshot(userId, score, trustLevel, totalTransaction, successfulTransaction,
                failedTransaction, ratingCount, policyVersion == null ? fallbackVersion : policyVersion);
    }

    public Map<String, Object> inputs() {
        var values = new LinkedHashMap<String, Object>();
        values.put("schemaVersion", 1);
        values.put("totalTransaction", totalTransaction);
        values.put("successfulTransaction", successfulTransaction);
        values.put("failedTransaction", failedTransaction);
        values.put("ratingCount", ratingCount);
        values.put("ratingScoreSum", ratingScoreSum);
        values.put("penaltyPoints", penaltyPoints);
        values.put("successfulAuctionCount", successfulAuctionCount);
        values.put("failedAuctionCount", failedAuctionCount);
        values.put("adjustmentOffset", adjustmentOffset);
        return values;
    }
}
