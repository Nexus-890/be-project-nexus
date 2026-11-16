package com.group5.ratingservice.reputation.policy;

import com.group5.ratingservice.reputation.config.ReputationProperties;
import com.group5.ratingservice.reputation.enums.TrustLevel;
import com.group5.ratingservice.reputation.model.ReputationState;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
@EnableConfigurationProperties(ReputationProperties.class)
public class ReputationPolicy {
    private final ReputationProperties properties;

    public ReputationPolicy(ReputationProperties properties) {
        if (properties.getVersion() == null || properties.getVersion().isBlank()
                || properties.getBasicMax() == null || properties.getTrustedMax() == null
                || properties.getBasicMax() >= properties.getTrustedMax()) {
            throw new IllegalArgumentException("A version and complete nonoverlapping trust thresholds are required");
        }
        this.properties = properties;
    }

    public String version() { return properties.getVersion(); }
    public int minimumToBid() { return properties.getMinimumToBid(); }
    public int minimumToSell() { return properties.getMinimumToSell(); }
    public int timeoutPenalty() { return properties.getTimeoutPenalty(); }

    public Map<String, Object> parameters() {
        return Map.ofEntries(Map.entry("version", version()), Map.entry("ratingWeight", properties.getRatingWeight()),
                Map.entry("successWeight", properties.getSuccessWeight()), Map.entry("basicMax", properties.getBasicMax()),
                Map.entry("trustedMax", properties.getTrustedMax()), Map.entry("timeoutPenalty", timeoutPenalty()),
                Map.entry("minimumToBid", minimumToBid()), Map.entry("minimumToSell", minimumToSell()),
                Map.entry("formulaId", "NEUTRAL_PLUS_RATING_SUCCESS_MINUS_PENALTY_V1"),
                Map.entry("rounding", "HALF_UP"), Map.entry("divisionScale", 12),
                Map.entry("timeoutSaturationRule", "REDUCE_VISIBLE_SCORE"));
    }

    public BigDecimal rawBase(ReputationState state) {
        BigDecimal result = BigDecimal.valueOf(50L - state.getPenaltyPoints());
        if (state.getRatingCount() > 0) {
            BigDecimal numerator = BigDecimal.valueOf(state.getRatingScoreSum())
                    .subtract(BigDecimal.valueOf(state.getRatingCount()).multiply(BigDecimal.valueOf(3)))
                    .multiply(BigDecimal.valueOf(properties.getRatingWeight()));
            result = result.add(numerator.divide(BigDecimal.valueOf(state.getRatingCount()).multiply(BigDecimal.valueOf(2)),
                    12, RoundingMode.HALF_UP));
        }
        if (state.getTotalTransaction() > 0) {
            result = result.add(BigDecimal.valueOf(state.getSuccessfulTransaction())
                    .multiply(BigDecimal.valueOf(properties.getSuccessWeight()))
                    .divide(BigDecimal.valueOf(state.getTotalTransaction()), 12, RoundingMode.HALF_UP));
        }
        return result;
    }

    public int score(ReputationState state) {
        BigDecimal value = rawBase(state).add(state.getAdjustmentOffset()).setScale(0, RoundingMode.HALF_UP);
        return value.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).intValueExact();
    }

    public TrustLevel level(int score, ReputationState state) {
        if (state.getTotalTransaction() == 0 && state.getRatingCount() == 0 && state.getPenaltyPoints() == 0
                && state.getAdjustmentOffset().signum() == 0) {
            return TrustLevel.NEW;
        }
        if (score <= properties.getBasicMax()) return TrustLevel.BASIC;
        if (score <= properties.getTrustedMax()) return TrustLevel.TRUSTED;
        return TrustLevel.TOP;
    }
}
