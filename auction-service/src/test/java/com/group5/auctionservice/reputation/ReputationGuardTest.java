package com.group5.auctionservice.reputation;

import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReputationGuardTest {
    private final UUID user = UUID.randomUUID();
    private final AuctionProperties properties = new AuctionProperties();

    @Test
    void disabledEnforcementSkipsTheClient() {
        properties.setReputationEnforcementEnabled(false);
        EligibilityClient client = mock(EligibilityClient.class);
        var guard = new ReputationGuard(properties, client);
        guard.requireEligible(user, EligibilityAction.BID);
        guard.requireEligible(user, EligibilityAction.SELL);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @EnumSource(EligibilityAction.class)
    void eligibleAtTheExactMinimumIsAllowedAndBelowMinimumGivesTheReason(EligibilityAction action) {
        properties.setReputationEnforcementEnabled(true);
        var allowed = new ReputationGuard(properties, (id, selected) -> decision(id, selected, true, 50, 50));
        allowed.requireEligible(user, action);
        var denied = new ReputationGuard(properties, (id, selected) -> decision(id, selected, false, 39, 40));
        assertThatThrownBy(() -> denied.requireEligible(user, action)).isInstanceOfSatisfying(DomainException.class,
                failure -> {
                    assertThat(failure.getCode()).isEqualTo(403);
                    assertThat(failure.getErrorCode()).isEqualTo("AUCTION_REPUTATION_TOO_LOW");
                    assertThat(failure.getMessage()).isEqualTo("The score is below the required minimum");
                    assertThat(failure.getProperties()).containsEntry("reason", failure.getMessage());
                });
    }

    @Test
    void changingTheExistingPropertiesSwitchesTheExistingGuard() {
        properties.setReputationEnforcementEnabled(false);
        var guard = new ReputationGuard(properties, (id, action) -> decision(id, action, false, 39, 40));
        guard.requireEligible(user, EligibilityAction.BID);
        properties.setReputationEnforcementEnabled(true);
        assertThatThrownBy(() -> guard.requireEligible(user, EligibilityAction.BID))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("AUCTION_REPUTATION_TOO_LOW");
        properties.setReputationEnforcementEnabled(false);
        guard.requireEligible(user, EligibilityAction.BID);
    }

    @ParameterizedTest
    @EnumSource(EligibilityAction.class)
    void temporaryRestrictionBlocksHighScoreAndReportsTheExpiry(EligibilityAction action) {
        var until = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(2);
        var guard = new ReputationGuard(properties, (id, selected) -> new EligibilityClient.Decision(
                id, selected, false, 99, "TOP", 40, "Temporarily restricted", "policy-v1", until));
        assertThatThrownBy(() -> guard.requireEligible(user, action)).isInstanceOfSatisfying(DomainException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo("AUCTION_REPUTATION_RESTRICTED");
            assertThat(failure.getCode()).isEqualTo(403);
            assertThat(failure.getProperties()).containsEntry("restrictedUntil", until).containsEntry("action", action.name());
        });
    }

    @Test
    void invalidOrMismatchedDomainDecisionsAndExceptionsFailClosed() {
        properties.setReputationEnforcementEnabled(true);
        for (EligibilityClient client : java.util.List.<EligibilityClient>of(
                (id, action) -> null,
                (id, action) -> decision(UUID.randomUUID(), action, true, 50, 40),
                (id, action) -> decision(id, EligibilityAction.SELL, true, 50, 40),
                (id, action) -> decision(id, action, true, 39, 40),
                (id, action) -> decision(id, action, false, 50, 40),
                (id, action) -> decision(id, action, true, 101, 40),
                (id, action) -> { throw new IllegalStateException("Unavailable"); })) {
            var guard = new ReputationGuard(properties, client);
            assertThatThrownBy(() -> guard.requireEligible(user, EligibilityAction.BID))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo("AUCTION_REPUTATION_UNAVAILABLE");
        }
    }

    private static EligibilityClient.Decision decision(UUID user, EligibilityAction action,
                                                       boolean eligible, int score, int minimum) {
        return new EligibilityClient.Decision(user, action, eligible, score, "BASIC", minimum,
                "The score is below the required minimum", "policy-v1");
    }
}
