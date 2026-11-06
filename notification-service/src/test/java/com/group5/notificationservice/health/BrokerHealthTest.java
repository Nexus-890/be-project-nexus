package com.group5.notificationservice.health;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerHealthTest {

    private static final Instant T1 = Instant.parse("2026-11-04T02:00:00Z");
    private static final Instant T2 = T1.plusSeconds(15);
    private static final Instant T3 = T2.plusSeconds(15);

    @Test
    void firstCheckUpRecordsNoFailure() {
        BrokerHealth health = BrokerHealth.unknown("kafka").observe(HealthStatus.UP, T1);

        assertThat(health.status()).isEqualTo(HealthStatus.UP);
        assertThat(health.lastCheckedAt()).isEqualTo(T1);
        assertThat(health.lastFailureAt()).isNull();
        assertThat(health.lastRecoveredAt()).isNull();
        assertThat(health.version()).isEqualTo(1);
    }

    @Test
    void goingDownRecordsTheFailureTimeAndANewVersion() {
        BrokerHealth up = BrokerHealth.unknown("kafka").observe(HealthStatus.UP, T1);

        BrokerHealth down = up.observe(HealthStatus.DOWN, T2);

        assertThat(down.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(down.lastFailureAt()).isEqualTo(T2);
        assertThat(down.lastRecoveredAt()).isNull();
        assertThat(down.version()).isEqualTo(2);
    }

    @Test
    void comingBackRecordsTheRecoveryTimeAndKeepsTheFailureTime() {
        BrokerHealth down = BrokerHealth.unknown("kafka").observe(HealthStatus.UP, T1).observe(HealthStatus.DOWN, T2);

        BrokerHealth up = down.observe(HealthStatus.UP, T3);

        assertThat(up.status()).isEqualTo(HealthStatus.UP);
        assertThat(up.lastCheckedAt()).isEqualTo(T3);
        assertThat(up.lastFailureAt()).isEqualTo(T2);
        assertThat(up.lastRecoveredAt()).isEqualTo(T3);
        assertThat(up.version()).isEqualTo(3);
    }

    @Test
    void stayingDownKeepsTheFirstFailureTimeAndTheVersion() {
        BrokerHealth down = BrokerHealth.unknown("kafka").observe(HealthStatus.DOWN, T1);

        BrokerHealth stillDown = down.observe(HealthStatus.DOWN, T2);

        assertThat(stillDown.lastCheckedAt()).isEqualTo(T2);
        assertThat(stillDown.lastFailureAt()).isEqualTo(T1);
        assertThat(stillDown.version()).isEqualTo(down.version());
    }
}
