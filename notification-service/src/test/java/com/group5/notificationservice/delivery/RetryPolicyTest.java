package com.group5.notificationservice.delivery;

import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final Instant FAILED_AT = Instant.parse("2026-11-04T10:00:00Z");

    private final DeliveryProperties properties = new DeliveryProperties();
    private final RetryPolicy policy = new RetryPolicy(properties);

    @Test
    void backoffDoublesFromOneSecondAndStopsAtThirtySeconds() {
        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.backoff(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoff(40)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void nextAttemptAddsAtMostTwentyPercentJitter() {
        properties.setRetryLimit(10);
        for (int i = 0; i < 200; i++) {
            Instant next = policy.nextAttemptAt(3, true, FAILED_AT).orElseThrow();
            assertThat(next).isAfter(FAILED_AT.plusSeconds(4))
                    .isBeforeOrEqualTo(FAILED_AT.plusMillis(4_800));
        }
    }

    @Test
    void permanentFailureIsNeverRetried() {
        assertThat(policy.nextAttemptAt(1, false, FAILED_AT)).isEmpty();
    }

    @Test
    void retryLimitCountsEverySendIncludingTheFirst() {
        properties.setRetryLimit(3);
        assertThat(policy.nextAttemptAt(1, true, FAILED_AT)).isPresent();
        assertThat(policy.nextAttemptAt(2, true, FAILED_AT)).isPresent();
        assertThat(policy.nextAttemptAt(3, true, FAILED_AT)).isEqualTo(Optional.empty());
    }
}
