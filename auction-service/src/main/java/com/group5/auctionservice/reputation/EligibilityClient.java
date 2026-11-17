package com.group5.auctionservice.reputation;

import java.util.UUID;

public interface EligibilityClient {
    Decision check(UUID userId, EligibilityAction action);

    record Decision(UUID userId, EligibilityAction action, boolean eligible, int score,
                    String trustLevel, int minimumScore, String reason, String policyVersion) {
    }
}
