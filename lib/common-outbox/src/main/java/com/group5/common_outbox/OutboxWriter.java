package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;

    public OutboxWriter(JdbcTemplate jdbc, JsonMapper jsonMapper, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    public UUID write(String aggregateType, String topic, EventEnvelope<?> envelope) {
        return write(aggregateType, 1, topic, envelope);
    }

    public UUID write(String aggregateType, int eventVersion, String topic, EventEnvelope<?> envelope) {
        return transaction.execute(status -> {
            Objects.requireNonNull(envelope, "envelope must not be null");
            requireText(aggregateType, "aggregateType", 50);
            requireText(topic, "topic", 80);
            if (eventVersion < 1) {
                throw new IllegalArgumentException("eventVersion must be positive");
            }

            jdbc.update("""
                    INSERT INTO outbox
                        (id, aggregate_type, aggregate_id, event_type, event_version,
                         topic, payload, trace_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """,
                    envelope.eventId(), aggregateType, envelope.aggregateId(), envelope.eventType(), eventVersion,
                    topic, jsonMapper.writeValueAsString(envelope), envelope.traceId(),
                    Timestamp.from(envelope.occurredAt()));
            return envelope.eventId();
        });
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
        }
    }
}
