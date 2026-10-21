package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = OutboxTestApplication.class, properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:../../notification-service/src/main/resources/db/migration/V1__init.sql",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers(disabledWithoutDocker = true)
class IdempotentConsumerTest {

    private static final String CONSUMER = "notification-service.order-created";
    private static final String OTHER_CONSUMER = "commerce-service.order-created";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IdempotentConsumer idempotentConsumer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void runsTheHandlerOnceWhenTheSameEventArrivesTwice() {
        EventEnvelope<String> envelope = newEnvelope();
        AtomicInteger runs = new AtomicInteger();

        boolean first = idempotentConsumer.handle(CONSUMER, envelope, event -> runs.incrementAndGet());
        boolean second = idempotentConsumer.handle(CONSUMER, envelope, event -> runs.incrementAndGet());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(runs).hasValue(1);
        assertThat(processedRows(envelope.eventId(), CONSUMER)).isEqualTo(1);
    }

    @Test
    void rejectsABlankOrTooLongConsumerName() {
        EventEnvelope<String> envelope = newEnvelope();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> idempotentConsumer.handle(" ", envelope, event -> { }));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> idempotentConsumer.handle("x".repeat(81), envelope, event -> { }));
        assertThat(processedRows(envelope.eventId())).isZero();
    }

    @Test
    void commitsTheHandlerWritesTogetherWithTheProcessedRow() {
        EventEnvelope<String> envelope = newEnvelope();

        idempotentConsumer.handle(CONSUMER, envelope, this::logEvent);

        assertThat(eventLogRows(envelope.eventId())).isEqualTo(1);
        assertThat(processedRows(envelope.eventId(), CONSUMER)).isEqualTo(1);
    }

    @Test
    void rollsBackTheHandlerWritesTogetherWithTheProcessedRow() {
        EventEnvelope<String> envelope = newEnvelope();

        assertThatThrownBy(() -> idempotentConsumer.handle(CONSUMER, envelope, event -> {
            logEvent(event);
            throw new IllegalStateException("handler failed after writing");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(eventLogRows(envelope.eventId())).isZero();
        assertThat(processedRows(envelope.eventId(), CONSUMER)).isZero();
    }

    @Test
    void runsTheHandlerOfEveryConsumerOfTheSameEvent() {
        EventEnvelope<String> envelope = newEnvelope();
        AtomicInteger runs = new AtomicInteger();

        boolean byNotification = idempotentConsumer.handle(CONSUMER, envelope, event -> runs.incrementAndGet());
        boolean byCommerce = idempotentConsumer.handle(OTHER_CONSUMER, envelope, event -> runs.incrementAndGet());

        assertThat(byNotification).isTrue();
        assertThat(byCommerce).isTrue();
        assertThat(runs).hasValue(2);
        assertThat(consumersThatHandled(envelope.eventId())).containsExactly(OTHER_CONSUMER, CONSUMER);
    }

    @Test
    void runsAgainAfterTheHandlerThrewAndKeepsOneRow() {
        EventEnvelope<String> envelope = newEnvelope();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> idempotentConsumer.handle(CONSUMER, envelope, event -> {
            runs.incrementAndGet();
            throw new IllegalStateException("first delivery failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(processedRows(envelope.eventId(), CONSUMER)).isZero();

        boolean retried = idempotentConsumer.handle(CONSUMER, envelope, event -> runs.incrementAndGet());

        assertThat(retried).isTrue();
        assertThat(runs).hasValue(2);
        assertThat(processedRows(envelope.eventId(), CONSUMER)).isEqualTo(1);
    }

    @RepeatedTest(10)
    void runsTheHandlerOnceWhenTwoDeliveriesOfOneEventArriveTogether() throws Exception {
        EventEnvelope<String> envelope = newEnvelope();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> delivery = () -> {
            start.await();
            return idempotentConsumer.handle(CONSUMER, envelope, event -> {
                runs.incrementAndGet();
                pause(Duration.ofMillis(200));
            });
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(delivery);
            Future<Boolean> second = pool.submit(delivery);
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(runs).hasValue(1);
        assertThat(processedRows(envelope.eventId(), CONSUMER)).isEqualTo(1);
    }

    private static EventEnvelope<String> newEnvelope() {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                "OrderCreated",
                UUID.randomUUID().toString(),
                Instant.now(),
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "{}");
    }

    private void logEvent(EventEnvelope<String> event) {
        jdbcTemplate.update(
                "INSERT INTO event_logs (event_id, event_type, occurred_at) VALUES (?, ?, ?)",
                event.eventId(), event.eventType(), Timestamp.from(event.occurredAt()));
    }

    private int processedRows(UUID eventId, String consumer) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, eventId, consumer);
    }

    private int processedRows(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    private int eventLogRows(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_logs WHERE event_id = ?", Integer.class, eventId);
    }

    private List<String> consumersThatHandled(UUID eventId) {
        return entityManager.createQuery(
                        "SELECT p.consumer FROM ProcessedEvent p WHERE p.eventId = :eventId ORDER BY p.consumer",
                        String.class)
                .setParameter("eventId", eventId)
                .getResultList();
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
