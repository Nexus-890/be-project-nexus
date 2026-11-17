package com.group5.ratingservice.reputation.policy;

import com.group5.ratingservice.reputation.config.ReputationProperties;
import com.group5.ratingservice.reputation.enums.TrustLevel;
import com.group5.ratingservice.reputation.model.ReputationState;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReputationPolicyTest {
    @Test
    void configuredExamplesUseExactInputsHalfUpAndNeutralNew() {
        ReputationPolicy policy = new ReputationPolicy(properties());
        ReputationState state = state();
        assertThat(policy.score(state)).isEqualTo(50);
        assertThat(policy.level(50, state)).isEqualTo(TrustLevel.NEW);
        state.setRatingCount(2);
        state.setRatingScoreSum(3);
        assertThat(policy.score(state)).isEqualTo(28);
        state.setRatingScoreSum(9);
        state.setTotalTransaction(1);
        state.setSuccessfulTransaction(1);
        assertThat(policy.score(state)).isEqualTo(93);
        state.setPenaltyPoints(1000);
        assertThat(policy.score(state)).isZero();
        state.setAdjustmentOffset(BigDecimal.valueOf(2000));
        assertThat(policy.score(state)).isEqualTo(100);
    }

    @ParameterizedTest
    @CsvSource({"0,BASIC", "59,BASIC", "60,TRUSTED", "79,TRUSTED", "80,TOP", "100,TOP"})
    void activeTrustRangesCoverEveryBoundary(int score, TrustLevel expected) {
        ReputationState state = state();
        state.setTotalTransaction(1);
        assertThat(new ReputationPolicy(properties()).level(score, state)).isEqualTo(expected);
    }

    @Test
    void missingOrInvalidPolicyHasValidationFailures() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ReputationProperties())).hasSize(3);
            ReputationProperties invalid = properties();
            invalid.setBasicMax(80);
            invalid.setTrustedMax(79);
            invalid.setMinimumToBid(101);
            invalid.setTimeoutPenalty(0);
            assertThat(validator.validate(invalid)).hasSize(3);
            assertThatThrownBy(() -> new ReputationPolicy(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static ReputationProperties properties() {
        var properties = new ReputationProperties();
        properties.setVersion("reputation-policy-1");
        properties.setBasicMax(59);
        properties.setTrustedMax(79);
        return properties;
    }

    private static ReputationState state() {
        var state = new ReputationState();
        state.setAdjustmentOffset(BigDecimal.ZERO);
        return state;
    }
}
