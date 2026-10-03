package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaSmokeRequest;
import com.group5.commerceservice.order.dto.KafkaSmokeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Profile("kafka-smoke")
public class KafkaSmokePublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaSmokePublisher.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final String topic;

    public KafkaSmokePublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper,
                              @Value("${nexus.kafka.smoke.topic:nexus.training.integration.v1}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    public KafkaSmokeResult publish(KafkaSmokeRequest request) {
        String eventId = UUID.randomUUID().toString();
        var event = new TrainingEvent(eventId, "TrainingEvent", 1, "commerce-service", "Training",
                request.key(), Instant.now().toString(), UUID.randomUUID().toString(),
                Map.of("message", request.message()));
        String value = jsonMapper.writeValueAsString(event);
        try {
            var metadata = kafkaTemplate.send(topic, request.key(), value).get(15, TimeUnit.SECONDS)
                    .getRecordMetadata();
            log.info("KAFKA_SMOKE_PUBLISHED eventId={} key={} topic={} partition={} offset={}",
                    eventId, request.key(), topic, metadata.partition(), metadata.offset());
            return new KafkaSmokeResult(eventId, request.key(), topic, metadata.partition(), metadata.offset());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka send interrupted", ex);
        } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException ex) {
            log.warn("KAFKA_SMOKE_SEND_FAILED eventId={} topic={}", eventId, topic, ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Kafka send was not acknowledged; delivery may still have occurred", ex);
        }
    }

    private record TrainingEvent(String eventId, String eventType, int eventVersion, String producer,
                                 String aggregateType, String aggregateId, String occurredAt, String traceId,
                                 Map<String, String> payload) {
    }
}
