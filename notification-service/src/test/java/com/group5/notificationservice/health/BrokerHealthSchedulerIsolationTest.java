package com.group5.notificationservice.health;

import com.group5.notificationservice.delivery.DeliveryService;
import com.group5.notificationservice.delivery.EmailSender;
import com.group5.notificationservice.delivery.RecipientDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
        "nexus.notification.group-id=broker-health-isolation-test",
        "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=user-service",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json",
        "nexus.notification.email-enabled=true",
        "nexus.notification.delivery.poll-interval=200ms",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1",
        "nexus.health.broker-interval=PT0.2S",
        "nexus.health.timeout=1s",
        "nexus.health.environment=iso",
        "management.tracing.enabled=false"
})
class BrokerHealthSchedulerIsolationTest {

    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18-alpine");
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new com.group5.common_test.RedisTestContainer("redis:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private final CountDownLatch release = new CountDownLatch(1);
    @Autowired private StringRedisTemplate redis;
    @MockitoBean private DeliveryService deliveries;
    @MockitoBean private RecipientDirectory recipients;
    @MockitoBean private EmailSender sender;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterEach
    void releaseTheEmailJob() {
        release.countDown();
    }

    @Test
    void aStuckEmailJobDoesNotStopTheKafkaCheck() throws InterruptedException {
        CountDownLatch stuck = new CountDownLatch(1);
        when(deliveries.claimNext(any(), any())).thenAnswer(invocation -> {
            stuck.countDown();
            release.await(60, TimeUnit.SECONDS);
            return Optional.empty();
        });
        assertThat(stuck.await(10, TimeUnit.SECONDS)).isTrue();

        Set<String> checks = new HashSet<>();
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (checks.size() < 3 && System.nanoTime() < until) {
            Object checkedAt = redis.opsForHash().get("iso:health:kafka", "lastCheckedAt");
            if (checkedAt != null) {
                checks.add(checkedAt.toString());
            }
            Thread.sleep(100);
        }

        assertThat(stuck.getCount()).isZero();
        assertThat(release.getCount()).isEqualTo(1);
        assertThat(checks).as("Kafka checks seen while the email job is stuck").hasSizeGreaterThanOrEqualTo(3);
    }
}
