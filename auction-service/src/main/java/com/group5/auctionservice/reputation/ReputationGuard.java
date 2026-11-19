package com.group5.auctionservice.reputation;

import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Map;
import java.util.UUID;

@Component
public class ReputationGuard {
    private static final Set<String> TRUST_LEVELS = Set.of("NEW", "BASIC", "TRUSTED", "TOP");
    private final AuctionProperties properties;
    private final EligibilityClient client;

    public ReputationGuard(AuctionProperties properties, EligibilityClient client) {
        this.properties = properties;
        this.client = client;
    }

    public void requireEligible(UUID userId, EligibilityAction action) {
        if (!properties.isReputationEnforcementEnabled()) {
            return;
        }
        EligibilityClient.Decision decision;
        try {
            decision = client.check(userId, action);
            validate(userId, action, decision);
        } catch (RuntimeException failure) {
            throw new DomainException(AuctionErrorCode.AUCTION_REPUTATION_UNAVAILABLE);
        }
        if (!decision.eligible()) {
            if (decision.restrictedUntil() != null) {
                throw new DomainException(AuctionErrorCode.AUCTION_REPUTATION_RESTRICTED, decision.reason(),
                        Map.of("reason", decision.reason(), "restrictedUntil", decision.restrictedUntil(), "action", action.name()));
            }
            throw new DomainException(AuctionErrorCode.AUCTION_REPUTATION_TOO_LOW, decision.reason(),
                    Map.of("reason", decision.reason()));
        }
    }

    static void validate(UUID userId, EligibilityAction action, EligibilityClient.Decision decision) {
        if (decision == null || !userId.equals(decision.userId()) || action != decision.action()
                || decision.score() < 0 || decision.score() > 100
                || decision.minimumScore() < 0 || decision.minimumScore() > 100
                || !TRUST_LEVELS.contains(decision.trustLevel())
                || decision.reason() == null || decision.reason().isBlank()
                || decision.policyVersion() == null || decision.policyVersion().isBlank()
                || decision.eligible() != (decision.score() >= decision.minimumScore() && decision.restrictedUntil() == null)) {
            throw new IllegalStateException("Invalid eligibility decision");
        }
    }
}
