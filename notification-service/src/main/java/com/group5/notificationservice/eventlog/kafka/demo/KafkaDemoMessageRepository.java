package com.group5.notificationservice.eventlog.kafka.demo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Profile("!kafka-smoke")
public class KafkaDemoMessageRepository {

    static final String CONSUMER_NAME = "notification-service.kafka-demo";
    private static final int MAX_MESSAGES = 100;

    private final JdbcTemplate jdbcTemplate;

    public KafkaDemoMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean saveIfNotProcessed(KafkaDemoEvent event) {
        int insertedProcessedEvent = jdbcTemplate.update("""
                INSERT INTO processed_events (event_id, consumer, handled_at)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id, consumer) DO NOTHING
                """,
                event.eventId(),
                CONSUMER_NAME,
                Timestamp.from(event.receivedAt())
        );

        if (insertedProcessedEvent == 0) {
            return false;
        }

        jdbcTemplate.update("""
                INSERT INTO event_logs (
                    event_id,
                    event_type,
                    message,
                    aggregate_type,
                    aggregate_id,
                    producer,
                    trace_id,
                    payload,
                    occurred_at,
                    received_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                event.eventId(),
                "KAFKA_DEMO_RECEIVED",
                "Kafka demo message received from " + event.topic(),
                "kafka-demo",
                event.topic() + "-" + event.partition() + "-" + event.offset(),
                "kafka",
                event.eventId().toString(),
                payloadJson(event),
                Timestamp.from(event.receivedAt()),
                Timestamp.from(event.receivedAt())
        );
        return true;
    }

    public List<KafkaDemoMessage> findLatest() {
        return jdbcTemplate.query("""
                SELECT
                    event_id,
                    payload -> 'kafka' ->> 'key' AS kafka_key,
                    payload -> 'kafka' ->> 'topic' AS kafka_topic,
                    (payload -> 'kafka' ->> 'partition')::int AS kafka_partition,
                    (payload -> 'kafka' ->> 'offset')::bigint AS kafka_offset,
                    received_at,
                    payload ->> 'rawValue' AS raw_value
                FROM event_logs
                WHERE event_type = 'KAFKA_DEMO_RECEIVED'
                ORDER BY received_at DESC
                LIMIT ?
                """, this::mapMessage, MAX_MESSAGES);
    }

    private KafkaDemoMessage mapMessage(ResultSet rs, int rowNumber) throws SQLException {
        return new KafkaDemoMessage(
                rs.getObject("event_id", UUID.class),
                rs.getString("kafka_key"),
                rs.getString("kafka_topic"),
                rs.getInt("kafka_partition"),
                rs.getLong("kafka_offset"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getString("raw_value")
        );
    }

    private String payloadJson(KafkaDemoEvent event) {
        Map<String, Object> kafka = new LinkedHashMap<>();
        kafka.put("key", event.key());
        kafka.put("topic", event.topic());
        kafka.put("partition", event.partition());
        kafka.put("offset", event.offset());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventIdSource", event.eventIdSource());
        payload.put("rawValue", event.value());
        payload.put("kafka", kafka);
        return JsonMapper.shared().writeValueAsString(payload);
    }
}
