package com.group5.notificationservice.health;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerHealthTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-04T09:00:00+07:00");
    private static final OffsetDateTime T2 = T1.plusSeconds(15);
    private static final OffsetDateTime T3 = T2.plusSeconds(15);

    @Test
    void firstCheckUpRecordsNoFailure() {
        BrokerHealth health = new BrokerHealth("kafka");

        health.record(HealthStatus.UP, T1);

        assertThat(health.getStatus()).isEqualTo(HealthStatus.UP);
        assertThat(health.getLastCheckedAt()).isEqualTo(T1);
        assertThat(health.getLastFailureAt()).isNull();
        assertThat(health.getLastRecoveredAt()).isNull();
    }

    @Test
    void goingDownRecordsTheFailureTime() {
        BrokerHealth health = new BrokerHealth("kafka");
        health.record(HealthStatus.UP, T1);

        health.record(HealthStatus.DOWN, T2);

        assertThat(health.getStatus()).isEqualTo(HealthStatus.DOWN);
        assertThat(health.getLastFailureAt()).isEqualTo(T2);
        assertThat(health.getLastRecoveredAt()).isNull();
    }

    @Test
    void comingBackRecordsTheRecoveryTimeAndKeepsTheFailureTime() {
        BrokerHealth health = new BrokerHealth("kafka");
        health.record(HealthStatus.UP, T1);
        health.record(HealthStatus.DOWN, T2);

        health.record(HealthStatus.UP, T3);

        assertThat(health.getStatus()).isEqualTo(HealthStatus.UP);
        assertThat(health.getLastCheckedAt()).isEqualTo(T3);
        assertThat(health.getLastFailureAt()).isEqualTo(T2);
        assertThat(health.getLastRecoveredAt()).isEqualTo(T3);
    }

    @Test
    void stayingDownKeepsTheFirstFailureTime() {
        BrokerHealth health = new BrokerHealth("kafka");
        health.record(HealthStatus.DOWN, T1);

        health.record(HealthStatus.DOWN, T2);

        assertThat(health.getLastCheckedAt()).isEqualTo(T2);
        assertThat(health.getLastFailureAt()).isEqualTo(T1);
    }
}
