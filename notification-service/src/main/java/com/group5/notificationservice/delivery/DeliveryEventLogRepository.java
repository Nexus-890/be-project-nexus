package com.group5.notificationservice.delivery;

import com.group5.common_events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class DeliveryEventLogRepository {

    private final JdbcTemplate jdbc;

    public DeliveryEventLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(EventEnvelope<?> envelope, String payloadJson) {
        jdbc.update("""
                INSERT INTO event_logs (event_id, event_type, aggregate_type, aggregate_id, producer,
                                        trace_id, payload, occurred_at)
                VALUES (?, ?, 'Notification', ?, 'notification-service', ?, CAST(? AS jsonb), ?)
                """, envelope.eventId(), envelope.eventType(), envelope.aggregateId(), envelope.traceId(),
                payloadJson, Timestamp.from(envelope.occurredAt()));
    }
}
