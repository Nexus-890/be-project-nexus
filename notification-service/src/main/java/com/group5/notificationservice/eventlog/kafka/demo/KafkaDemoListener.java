package com.group5.notificationservice.eventlog.kafka.demo;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("!kafka-smoke")
public class KafkaDemoListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaDemoListener.class);
    private static final long MAX_PROCESSING_DELAY_MS = 120_000L;

    private final KafkaDemoMessageRepository repository;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final Duration processingDelay;
    private final String clientId;

    @Autowired
    public KafkaDemoListener(
            KafkaDemoMessageRepository repository,
            @Value("${nexus.kafka.demo.processing-delay-ms:0}") long processingDelayMs,
            @Value("${nexus.kafka.demo.client-id:notification-demo}") String clientId
    ) {
        this(repository, Clock.systemUTC(), JsonMapper.shared(), validateProcessingDelay(processingDelayMs), clientId);
    }

    KafkaDemoListener(
            KafkaDemoMessageRepository repository,
            Clock clock,
            JsonMapper jsonMapper,
            Duration processingDelay,
            String clientId
    ) {
        this.repository = repository;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.processingDelay = processingDelay;
        this.clientId = clientId;
    }

    @KafkaListener(
            topics = "${nexus.kafka.demo.topic:nexus.training.integration.v1}",
            groupId = "${nexus.kafka.demo.group-id:nexus.notification.kafka-demo.v1}",
            clientIdPrefix = "${nexus.kafka.demo.client-id:notification-demo}",
            concurrency = "1"
    )
    public void receive(ConsumerRecord<String, String> record) {
        KafkaDemoEvent event = toEvent(record);
        boolean stored = repository.saveIfNotProcessed(event);

        log.info(
                "KAFKA_DEMO_RECEIVED stored={} eventId={} key={} topic={} partition={} offset={} clientId={}",
                stored,
                event.eventId(),
                record.key(),
                record.topic(),
                record.partition(),
                record.offset(),
                clientId
        );

        delayBeforeReturnIfConfigured(record);
    }

    private KafkaDemoEvent toEvent(ConsumerRecord<String, String> record) {
        EventId eventId = eventIdFrom(record);
        return new KafkaDemoEvent(
                eventId.value(),
                eventId.source(),
                record.key(),
                record.topic(),
                record.partition(),
                record.offset(),
                Instant.now(clock),
                record.value()
        );
    }

    private EventId eventIdFrom(ConsumerRecord<String, String> record) {
        String rawEventId = rawEventIdFrom(record.value());
        if (rawEventId != null) {
            try {
                return new EventId(UUID.fromString(rawEventId), "payload.eventId");
            } catch (IllegalArgumentException ex) {
                log.warn("KAFKA_DEMO_INVALID_EVENT_ID clientId={} eventId={}", clientId, rawEventId);
            }
        }

        String fallbackSeed = record.topic() + ":" + record.partition() + ":" + record.offset() + ":" + record.value();
        return new EventId(UUID.nameUUIDFromBytes(fallbackSeed.getBytes(StandardCharsets.UTF_8)), "kafka.metadata");
    }

    private String rawEventIdFrom(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            JsonNode root = jsonMapper.readTree(value);
            JsonNode eventId = root.path("eventId");
            String parsed = eventId.asString(null);
            return parsed == null || parsed.isBlank() ? null : parsed;
        } catch (JacksonException ex) {
            log.warn("KAFKA_DEMO_INVALID_JSON clientId={} error={}", clientId, ex.getMessage());
            return null;
        }
    }

    private void delayBeforeReturnIfConfigured(ConsumerRecord<String, String> record) {
        if (processingDelay.isZero()) {
            return;
        }

        log.info(
                "KAFKA_DEMO_DELAY_BEFORE_RETURN delayMs={} key={} topic={} partition={} offset={} clientId={}",
                processingDelay.toMillis(),
                record.key(),
                record.topic(),
                record.partition(),
                record.offset(),
                clientId
        );
        try {
            Thread.sleep(processingDelay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during kafka demo processing delay", ex);
        }
    }

    static Duration validateProcessingDelay(long processingDelayMs) {
        if (processingDelayMs < 0 || processingDelayMs > MAX_PROCESSING_DELAY_MS) {
            throw new IllegalArgumentException("nexus.kafka.demo.processing-delay-ms must be between 0 and 120000");
        }
        return Duration.ofMillis(processingDelayMs);
    }

    private record EventId(UUID value, String source) {
    }
}
