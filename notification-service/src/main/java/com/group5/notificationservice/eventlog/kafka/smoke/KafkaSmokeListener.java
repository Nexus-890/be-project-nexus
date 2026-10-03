package com.group5.notificationservice.eventlog.kafka.smoke;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("kafka-smoke")
public class KafkaSmokeListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaSmokeListener.class);
    private static final long MAX_PROCESSING_DELAY_MS = 120_000L;

    private final KafkaSmokeMessageStore store;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final Duration processingDelay;
    private final String clientId;

    @Autowired
    public KafkaSmokeListener(
            KafkaSmokeMessageStore store,
            @Value("${nexus.kafka.smoke.processing-delay-ms:0}") long processingDelayMs,
            @Value("${nexus.kafka.smoke.client-id:notification-smoke}") String clientId
    ) {
        this(store, Clock.systemUTC(), JsonMapper.shared(), validateProcessingDelay(processingDelayMs), clientId);
    }

    KafkaSmokeListener(
            KafkaSmokeMessageStore store,
            Clock clock,
            JsonMapper jsonMapper,
            Duration processingDelay,
            String clientId
    ) {
        this.store = store;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.processingDelay = processingDelay;
        this.clientId = clientId;
    }

    @KafkaListener(
            topics = "${nexus.kafka.smoke.topic:nexus.training.integration.v1}",
            groupId = "${nexus.kafka.smoke.group-id:nexus.notification.kafka-smoke.v1}",
            clientIdPrefix = "${nexus.kafka.smoke.client-id:notification-smoke}",
            concurrency = "1"
    )
    public void receive(ConsumerRecord<String, String> record) {
        String eventId = eventIdFrom(record.value());
        KafkaSmokeMessage message = new KafkaSmokeMessage(
                eventId,
                record.key(),
                record.topic(),
                record.partition(),
                record.offset(),
                Instant.now(clock),
                record.value()
        );
        store.add(message);

        log.info(
                "KAFKA_SMOKE_RECEIVED eventId={} key={} topic={} partition={} offset={} clientId={}",
                eventId,
                record.key(),
                record.topic(),
                record.partition(),
                record.offset(),
                clientId
        );

        delayBeforeReturnIfConfigured(record);
    }

    private void delayBeforeReturnIfConfigured(ConsumerRecord<String, String> record) {
        if (processingDelay.isZero()) {
            return;
        }

        log.info(
                "KAFKA_SMOKE_DELAY_BEFORE_RETURN delayMs={} key={} topic={} partition={} offset={} clientId={}",
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
            throw new IllegalStateException("Interrupted during kafka smoke processing delay", ex);
        }
    }

    private String eventIdFrom(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            JsonNode root = jsonMapper.readTree(value);
            JsonNode eventId = root.path("eventId");
            String parsed = eventId.asString(null);
            return parsed == null || parsed.isBlank() ? null : parsed;
        } catch (JacksonException ex) {
            log.warn("KAFKA_SMOKE_INVALID_JSON clientId={} error={}", clientId, ex.getMessage());
            return null;
        }
    }

    static Duration validateProcessingDelay(long processingDelayMs) {
        if (processingDelayMs < 0 || processingDelayMs > MAX_PROCESSING_DELAY_MS) {
            throw new IllegalArgumentException("nexus.kafka.smoke.processing-delay-ms must be between 0 and 120000");
        }
        return Duration.ofMillis(processingDelayMs);
    }
}
