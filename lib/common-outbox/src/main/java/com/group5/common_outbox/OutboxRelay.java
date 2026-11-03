package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class OutboxRelay {

    static final String CLAIM_SQL = """
            SELECT * FROM outbox
            WHERE published_at IS NULL
            ORDER BY occurred_at
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """;
    static final String MARK_PUBLISHED_SQL = """
            UPDATE outbox SET published_at = clock_timestamp()
            WHERE id = ? AND published_at IS NULL
            """;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final JavaType envelopeType;
    private final KafkaTemplate<String, EventEnvelope<?>> kafka;
    private final TransactionTemplate transaction;
    private final long sendTimeoutMillis;

    public OutboxRelay(JdbcTemplate jdbc, JsonMapper jsonMapper,
                       KafkaTemplate<String, EventEnvelope<?>> kafka,
                       PlatformTransactionManager transactionManager, Duration sendTimeout) {
        if (sendTimeout == null || sendTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("sendTimeout must be at least one millisecond");
        }
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.envelopeType = jsonMapper.getTypeFactory().constructParametricType(EventEnvelope.class, JsonNode.class);
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sendTimeoutMillis = sendTimeout.toMillis();
    }

    @Scheduled(fixedDelayString = "${nexus.outbox.relay.fixed-delay:1000}",
            initialDelayString = "${nexus.outbox.relay.initial-delay:1000}")
    public void publishPending() {
        transaction.executeWithoutResult(status -> {
            for (OutboxEntry entry : jdbc.query(CLAIM_SQL, OutboxRelay::mapEntry)) {
                try {
                    EventEnvelope<?> envelope = jsonMapper.readValue(entry.payload(), envelopeType);
                    kafka.send(entry.topic(), entry.aggregateId(), envelope)
                            .get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("Outbox relay interrupted: eventId={} topic={}", entry.id(), entry.topic());
                    break;
                } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                    log.warn("Outbox publish failed; will retry: eventId={} topic={}", entry.id(), entry.topic(), ex);
                    break;
                }

                jdbc.update(MARK_PUBLISHED_SQL, entry.id());
            }
        });
    }

    private static OutboxEntry mapEntry(ResultSet row, int rowNumber) throws SQLException {
        Timestamp publishedAt = row.getTimestamp("published_at");
        return new OutboxEntry(
                row.getObject("id", UUID.class), row.getString("aggregate_type"), row.getString("aggregate_id"),
                row.getString("event_type"), row.getInt("event_version"), row.getString("topic"),
                row.getString("payload"), row.getString("trace_id"), row.getTimestamp("occurred_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }
}
