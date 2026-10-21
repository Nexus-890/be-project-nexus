package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest(classes = OutboxTestApplication.class, properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:../../notification-service/src/main/resources/db/migration/V1__init.sql",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers(disabledWithoutDocker = true)
class IdempotentConsumerTest {

    private static final String CONSUMER = "notification-service.order-created";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IdempotentConsumer idempotentConsumer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private static EventEnvelope<String> newEnvelope() {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                "OrderCreated",
                UUID.randomUUID().toString(),
                Instant.now(),
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "{}");
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
}
