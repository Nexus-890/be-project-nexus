package com.group5.auctionservice.reputation;

import java.util.UUID;
import java.time.OffsetDateTime;

public interface EligibilityClient {
    Decision check(UUID userId, EligibilityAction action);

    record Decision(UUID userId, EligibilityAction action, boolean eligible, int score,
                    String trustLevel, int minimumScore, String reason, String policyVersion, OffsetDateTime restrictedUntil) {
        public Decision(UUID userId, EligibilityAction action, boolean eligible, int score,
                        String trustLevel, int minimumScore, String reason, String policyVersion) {
            this(userId, action, eligible, score, trustLevel, minimumScore, reason, policyVersion, null);
        }
    }
}
