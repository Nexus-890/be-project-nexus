package com.group5.notificationservice.health;

import java.time.Instant;

public record BrokerHealth(
        String name,
        HealthStatus status,
        Instant lastCheckedAt,
        Instant lastFailureAt,
        Instant lastRecoveredAt,
        long version) {

    public static BrokerHealth unknown(String name) {
        return new BrokerHealth(name, HealthStatus.UNKNOWN, null, null, null, 0);
    }

    // VERSION CHỈ TĂNG KHI ĐỔI TRẠNG THÁI -> MỖI VERSION LÀ ĐÚNG 1 LẦN CHUYỂN (healthVersion TRONG EVENT)
    // RECOVERY KHÔNG XÓA lastFailureAt
    public BrokerHealth observe(HealthStatus observed, Instant checkedAt) {
        if (observed == status) {
            return new BrokerHealth(name, status, checkedAt, lastFailureAt, lastRecoveredAt, version);
        }
        Instant failure = observed == HealthStatus.DOWN ? checkedAt : lastFailureAt;
        Instant recovered = observed == HealthStatus.UP && status == HealthStatus.DOWN ? checkedAt : lastRecoveredAt;
        return new BrokerHealth(name, observed, checkedAt, failure, recovered, version + 1);
    }
}
