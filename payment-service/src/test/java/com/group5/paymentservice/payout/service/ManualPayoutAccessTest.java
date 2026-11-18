package com.group5.paymentservice.payout.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

class ManualPayoutAccessTest {
    private final ManualPayoutAccess policy = new ManualPayoutAccess(new ManualPayoutProperties());
    private final UUID seller = UUID.randomUUID(), executor = UUID.randomUUID();
    private final CurrentUser admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
    private final Map<String, Object> payout = Map.of("seller_id", seller, "status", "SUCCEEDED");
    private final Map<String, Object> attempt =
            Map.of("state", "SUCCEEDED", "claimed_by", executor);

    @Test
    void staffExceptionsAllowResolutionButNeverSellerDisputeReview() {
        assertThat(policy.actions(admin, payout, attempt, true, false))
                .contains("RESOLVE")
                .doesNotContain("REVIEW_DISPUTE");
        assertThat(policy.actions(admin, payout, attempt, true, true))
                .contains("RESOLVE", "REVIEW_DISPUTE");
    }

    @Test
    void sellerDisputeReviewStillRequiresAdminAndAnOpenCase() {
        var support = new CurrentUser(executor, Set.of("SUPPORT_STAFF"), Set.of());
        assertThat(policy.actions(support, payout, attempt, true, true))
                .doesNotContain("REVIEW_DISPUTE", "RESOLVE");
        assertThat(policy.actions(admin, payout, attempt, false, false))
                .doesNotContain("REVIEW_DISPUTE", "RESOLVE");
        var self = new CurrentUser(seller, Set.of("ADMIN", "SELLER"), Set.of());
        assertThat(policy.actions(self, payout, attempt, true, true)).isEmpty();
    }
}
