package com.group5.notificationservice.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "broker_health")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrokerHealth {

    @Id
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "health_status")
    private HealthStatus status;

    @Column(name = "last_checked_at", nullable = false)
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_failure_at")
    private OffsetDateTime lastFailureAt;

    @Column(name = "last_recovered_at")
    private OffsetDateTime lastRecoveredAt;

    public BrokerHealth(String name) {
        this.name = name;
    }

    public void record(HealthStatus observed, OffsetDateTime checkedAt) {
        if (observed == HealthStatus.DOWN && status != HealthStatus.DOWN) {
            lastFailureAt = checkedAt;
        }
        if (observed == HealthStatus.UP && status == HealthStatus.DOWN) {
            lastRecoveredAt = checkedAt;
        }
        status = observed;
        lastCheckedAt = checkedAt;
    }
}
