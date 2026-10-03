package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaDemoRequest;
import com.group5.commerceservice.order.dto.KafkaDemoResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Profile("!kafka-smoke")
public class KafkaDemoPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaDemoPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String topic;

    @Autowired
    public KafkaDemoPublisher(KafkaTemplate<String, String> kafkaTemplate,
                              JdbcTemplate jdbcTemplate,
                              JsonMapper jsonMapper,
                              @Value("${nexus.kafka.demo.topic:nexus.training.integration.v1}") String topic) {
        this(kafkaTemplate, jdbcTemplate, jsonMapper, Clock.systemUTC(), topic);
    }

    KafkaDemoPublisher(KafkaTemplate<String, String> kafkaTemplate,
                       JdbcTemplate jdbcTemplate,
                       JsonMapper jsonMapper,
                       Clock clock,
                       String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.topic = topic;
    }

    public KafkaDemoResult publish(KafkaDemoRequest request) {
        UUID outboxId = UUID.randomUUID();
        String eventId = outboxId.toString();
        String traceId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now(clock);
        var event = new TrainingEvent(eventId, "TrainingEvent", 1, "commerce-service", "Training",
                request.key(), occurredAt.toString(), traceId, Map.of("message", request.message()));
        String value = jsonMapper.writeValueAsString(event);

        insertOutbox(outboxId, request.key(), value, traceId, occurredAt);

        try {
            var metadata = kafkaTemplate.send(topic, request.key(), value).get(15, TimeUnit.SECONDS)
                    .getRecordMetadata();
            markOutboxPublished(outboxId, Instant.now(clock));
            log.info("KAFKA_DEMO_PUBLISHED outboxId={} eventId={} key={} topic={} partition={} offset={}",
                    outboxId, eventId, request.key(), topic, metadata.partition(), metadata.offset());
            return new KafkaDemoResult(outboxId, eventId, request.key(), topic, metadata.partition(), metadata.offset());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka send interrupted", ex);
        } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException ex) {
            log.warn("KAFKA_DEMO_SEND_FAILED outboxId={} eventId={} topic={}", outboxId, eventId, topic, ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Kafka send was not acknowledged; delivery may still have occurred", ex);
        }
    }

    private void insertOutbox(UUID outboxId, String aggregateId, String payload, String traceId, Instant occurredAt) {
        jdbcTemplate.update("""
                        INSERT INTO outbox
                            (id, aggregate_type, aggregate_id, event_type, event_version, topic, payload, trace_id, occurred_at)
                        VALUES (?, 'Training', ?, 'TrainingEvent', 1, ?, ?::jsonb, ?, ?)
                        """,
                outboxId, aggregateId, topic, payload, traceId, Timestamp.from(occurredAt));
    }

    private void markOutboxPublished(UUID outboxId, Instant publishedAt) {
        jdbcTemplate.update("UPDATE outbox SET published_at = ? WHERE id = ?", Timestamp.from(publishedAt), outboxId);
    }

    private record TrainingEvent(String eventId, String eventType, int eventVersion, String producer,
                                 String aggregateType, String aggregateId, String occurredAt, String traceId,
                                 Map<String, String> payload) {
    }
}
