package com.group5.notificationservice.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/notification-persistence-test.properties",
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "nexus.notification.group-id=broker-health-test",
        "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=user-service",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json",
        "nexus.health.timeout=1s",
        "management.tracing.enabled=false"
})
class BrokerHealthJobIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired private BrokerHealthRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void theScheduledJobStoresAnUnreachableBrokerAsDownUnderAShedLock() throws InterruptedException {
        Optional<BrokerHealth> kafka = waitForKafkaRow(Duration.ofSeconds(20));

        assertThat(kafka).hasValueSatisfying(health -> {
            assertThat(health.getStatus()).isEqualTo(HealthStatus.DOWN);
            assertThat(health.getLastCheckedAt()).isNotNull();
            assertThat(health.getLastFailureAt()).isNotNull();
            assertThat(health.getLastRecoveredAt()).isNull();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shedlock WHERE name = 'brokerHealthJob'", Integer.class)).isEqualTo(1);
    }

    private Optional<BrokerHealth> waitForKafkaRow(Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<BrokerHealth> kafka = repository.findById(BrokerHealthJob.KAFKA);
            if (kafka.isPresent()) {
                return kafka;
            }
            Thread.sleep(200);
        }
        return Optional.empty();
    }
}
