package com.group5.notificationservice.health;

import com.group5.notificationservice.eventlog.EventLogRecorder;
import com.group5.notificationservice.shared.config.HealthProperties;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
        "nexus.notification.group-id=broker-health-test",
        "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=user-service",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json",
        "nexus.health.timeout=1s",
        "nexus.health.environment=it",
        "management.tracing.enabled=false"
})
class BrokerHealthJobIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18-alpine");
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new com.group5.common_test.RedisTestContainer("redis:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EventLogRecorder recorder;
    @Autowired private JsonMapper mapper;
    @Autowired private LockProvider lockProvider;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    void theScheduledJobKeepsTheStateInRedisUnderARedisLockAndLogsTheFailure() throws InterruptedException {
        Map<Object, Object> state = waitForState("it:health:kafka", Duration.ofSeconds(20));

        assertThat(redis.hasKey("job-lock:it:brokerHealthJob")).isTrue();
        assertThat(state).containsEntry("status", "DOWN").containsEntry("version", "1")
                .containsKeys("lastCheckedAt", "lastFailureAt").doesNotContainKey("lastRecoveredAt");
        long recorded = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (redis.hasKey("it:health:kafka:pending") && System.nanoTime() < recorded) {
            Thread.sleep(50);
        }
        assertThat(redis.hasKey("it:health:kafka:pending")).isFalse();
        assertThat(jdbc.queryForList("""
                SELECT event_type, payload->'payload'->>'previousStatus' AS previous,
                       payload->'payload'->>'healthVersion' AS version, source_topic
                FROM event_logs WHERE aggregate_id = 'it:kafka'
                """)).singleElement().satisfies(row -> {
            assertThat(row.get("event_type")).isEqualTo(HealthTransitionEvents.UNAVAILABLE);
            assertThat(row.get("previous")).isEqualTo("UNKNOWN");
            assertThat(row.get("version")).isEqualTo("1");
            assertThat(row.get("source_topic")).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT to_regclass('broker_health') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void aTransitionQueuedWhilePostgresIsDownIsLoggedOnceOnALaterRound() {
        AtomicBoolean postgresDown = new AtomicBoolean(true);
        EventLogRecorder flaky = mock(EventLogRecorder.class);
        when(flaky.recordRaw(anyString(), isNull())).thenAnswer(invocation -> {
            if (postgresDown.get()) {
                throw new DataAccessResourceFailureException("event_logs unreachable");
            }
            return recorder.recordRaw(invocation.getArgument(0), null);
        });
        KafkaBrokerProbe probe = mock(KafkaBrokerProbe.class);
        HealthProperties properties = new HealthProperties(List.of("user-service"),
                URI.create("http://127.0.0.1:1/realms/nexus"), Duration.ofSeconds(1), "retry");
        HealthStateStore store = new HealthStateStore(redis, properties);
        @SuppressWarnings("unchecked")
        HealthTransitionEvents events = new HealthTransitionEvents(mock(ObjectProvider.class), properties);
        BrokerHealthJob job = new BrokerHealthJob(probe, store, events, mapper);
        HealthEventWriter writer = new HealthEventWriter(store, flaky, new DefaultLockingTaskExecutor(lockProvider),
                20, Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofMinutes(5));

        when(probe.reachable()).thenReturn(false);
        job.checkBrokers();
        writer.drain();
        assertThat(store.find(BrokerHealthJob.KAFKA)).hasValueSatisfying(health ->
                assertThat(health.status()).isEqualTo(HealthStatus.DOWN));
        assertThat(store.pendingEvents(BrokerHealthJob.KAFKA)).hasSize(1);
        assertThat(logged("retry:kafka")).isEmpty();

        postgresDown.set(false);
        writer.drain();
        writer.drain();
        assertThat(store.pendingEvents(BrokerHealthJob.KAFKA)).isEmpty();
        assertThat(logged("retry:kafka")).containsExactly(HealthTransitionEvents.UNAVAILABLE);

        when(probe.reachable()).thenReturn(true);
        job.checkBrokers();
        job.checkBrokers();
        writer.drain();
        BrokerHealth recovered = store.find(BrokerHealthJob.KAFKA).orElseThrow();
        assertThat(recovered.status()).isEqualTo(HealthStatus.UP);
        assertThat(recovered.version()).isEqualTo(2);
        assertThat(recovered.lastRecoveredAt()).isAfterOrEqualTo(recovered.lastFailureAt());
        assertThat(logged("retry:kafka"))
                .containsExactly(HealthTransitionEvents.UNAVAILABLE, HealthTransitionEvents.RECOVERED);
    }

    @Test
    void aHangingPostgresNeverDelaysTheKafkaCheck() throws Exception {
        KafkaBrokerProbe probe = mock(KafkaBrokerProbe.class);
        HealthProperties properties = new HealthProperties(List.of("user-service"),
                URI.create("http://127.0.0.1:1/realms/nexus"), Duration.ofSeconds(1), "hang");
        HealthStateStore store = new HealthStateStore(redis, properties);
        @SuppressWarnings("unchecked")
        HealthTransitionEvents events = new HealthTransitionEvents(mock(ObjectProvider.class), properties);
        BrokerHealthJob job = new BrokerHealthJob(probe, store, events, mapper);
        HealthEventWriter writer = new HealthEventWriter(store, recorder, new DefaultLockingTaskExecutor(lockProvider),
                20, Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofSeconds(1));
        writer.afterPropertiesSet();
        try {
            try (AutoCloseable blockedDatabase = blockPostgresWrites()) {
                when(probe.reachable()).thenReturn(false);
                job.checkBrokers();
                Thread.sleep(500);

                when(probe.reachable()).thenReturn(true);
                long started = System.nanoTime();
                job.checkBrokers();
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
                assertThat(store.find(BrokerHealthJob.KAFKA)).hasValueSatisfying(health -> {
                    assertThat(health.status()).isEqualTo(HealthStatus.UP);
                    assertThat(health.version()).isEqualTo(2);
                });
                assertThat(store.pendingEvents(BrokerHealthJob.KAFKA)).hasSize(2);
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!store.pendingEvents(BrokerHealthJob.KAFKA).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(store.pendingEvents(BrokerHealthJob.KAFKA)).isEmpty();
            assertThat(logged("hang:kafka"))
                    .containsExactlyInAnyOrder(HealthTransitionEvents.UNAVAILABLE, HealthTransitionEvents.RECOVERED);
        } finally {
            writer.destroy();
        }
    }

    private AutoCloseable blockPostgresWrites() throws Exception {
        if (System.getProperty("nexus.test.jdbc-url", System.getenv("TEST_POSTGRES_ADMIN_URL")) == null) {
            POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
            return () -> POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
        // Native mode blocks the actual event-log JDBC write with an exclusive SQL lock.
        // Kafka checks and the pending-event assertions below remain unchanged.
        var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) { statement.execute("LOCK TABLE event_logs IN ACCESS EXCLUSIVE MODE"); }
        return () -> { try { connection.rollback(); } finally { connection.close(); } };
    }

    private List<String> logged(String aggregateId) {
        return jdbc.queryForList("SELECT event_type FROM event_logs WHERE aggregate_id = ? ORDER BY occurred_at",
                String.class, aggregateId);
    }

    private Map<Object, Object> waitForState(String key, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            Map<Object, Object> state = redis.opsForHash().entries(key);
            if (!state.isEmpty()) {
                return state;
            }
            Thread.sleep(100);
        }
        return Map.of();
    }
}
