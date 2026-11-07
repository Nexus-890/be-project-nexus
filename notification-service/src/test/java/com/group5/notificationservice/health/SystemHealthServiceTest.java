package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.BrokerHealthResponse;
import com.group5.notificationservice.health.dto.ServiceHealthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemHealthServiceTest {

    private static final Instant CHECKED = Instant.parse("2026-11-04T02:31:00Z");

    private ServiceHealthChecker checker;
    private HealthStateStore store;
    private SystemHealthService service;

    @BeforeEach
    void setup() {
        checker = mock(ServiceHealthChecker.class);
        store = mock(HealthStateStore.class);
        service = new SystemHealthService(checker, store);
        when(checker.checkAll()).thenReturn(List.of(new ServiceHealthResponse("user-service", HealthStatus.UP)));
    }

    @Test
    void brokersComeFromTheRedisState() {
        when(store.find(BrokerHealthJob.KAFKA)).thenReturn(Optional.of(
                new BrokerHealth(BrokerHealthJob.KAFKA, HealthStatus.UP, CHECKED, null, null, 1)));

        assertThat(service.systemHealth().brokers()).singleElement().satisfies(broker -> {
            assertThat(broker.status()).isEqualTo(HealthStatus.UP);
            assertThat(broker.lastCheckedAt()).isEqualTo(CHECKED);
        });
    }

    @Test
    void aBrokerNotCheckedYetIsUnknown() {
        when(store.find(BrokerHealthJob.KAFKA)).thenReturn(Optional.empty());

        assertThat(service.brokers()).containsExactly(
                new BrokerHealthResponse(BrokerHealthJob.KAFKA, HealthStatus.UNKNOWN, null, null, null));
    }

    @Test
    void anUnreachableRedisDegradesTheBrokersButKeepsTheServices() {
        when(store.find(BrokerHealthJob.KAFKA)).thenThrow(new RedisConnectionFailureException("redis down"));

        var health = service.systemHealth();

        assertThat(health.services()).containsExactly(new ServiceHealthResponse("user-service", HealthStatus.UP));
        assertThat(health.brokers()).singleElement().satisfies(broker -> {
            assertThat(broker.status()).isEqualTo(HealthStatus.UNKNOWN);
            assertThat(broker.lastCheckedAt()).isNull();
        });
        assertThat(service.brokers()).singleElement()
                .satisfies(broker -> assertThat(broker.status()).isEqualTo(HealthStatus.UNKNOWN));
    }
}
