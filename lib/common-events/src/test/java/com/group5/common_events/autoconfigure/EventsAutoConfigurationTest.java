package com.group5.common_events.autoconfigure;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaUtils;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventsAutoConfigurationTest {

    private static final EventEnvelope<Map<String, String>> SENT = new EventEnvelope<>(
            UUID.fromString("01936b2a-7c4e-7000-8000-000000000001"),
            "ProductCreated",
            "69000000-0000-4000-8000-000000000201",
            Instant.parse("2026-10-19T08:00:00Z"),
            "4bf92f3577b34da6a3ce929d0e0e4736",
            Map.of("name", "iPhone 15"));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, EventsAutoConfiguration.class));

    @Test
    void addsTheEventBeansAndKeepsTheDefaultStringTemplate() {
        contextRunner.run(context -> {
            assertThat(context).hasBean(EventKafka.TEMPLATE);
            assertThat(context).hasBean(EventKafka.LISTENER_CONTAINER_FACTORY);
            assertThat(context).hasSingleBean(EventEnvelopeFactory.class);
            assertThat(context).hasBean("kafkaTemplate");
            assertThat(context).hasBean("kafkaListenerContainerFactory");
        });
    }

    @Test
    void readsBackTheSameEnvelopeWithNoNullField() {
        contextRunner.run(context -> {
            byte[] json = serializer(context).serialize(Topics.CATALOG, new RecordHeaders(), SENT);

            Object received = deserializer(context).deserialize(Topics.CATALOG, new RecordHeaders(), json);

            assertThat(received).isInstanceOf(EventEnvelope.class).hasNoNullFieldsOrProperties();
            EventEnvelope<?> envelope = (EventEnvelope<?>) received;
            assertThat(envelope.eventId()).isEqualTo(SENT.eventId());
            assertThat(envelope.eventType()).isEqualTo(SENT.eventType());
            assertThat(envelope.aggregateId()).isEqualTo(SENT.aggregateId());
            assertThat(envelope.occurredAt()).isEqualTo(SENT.occurredAt());
            assertThat(envelope.traceId()).isEqualTo(SENT.traceId());
            assertThat(envelope.payload()).isInstanceOf(JsonNode.class);
            assertThat(((JsonNode) envelope.payload()).get("name").asString()).isEqualTo("iPhone 15");
        });
    }

    @Test
    void writesPlainJsonWithoutJavaTypeHeaders() {
        contextRunner.run(context -> {
            Headers headers = new RecordHeaders();

            byte[] json = serializer(context).serialize(Topics.CATALOG, headers, SENT);

            assertThat(headers.toArray()).isEmpty();
            assertThat(new String(json, StandardCharsets.UTF_8))
                    .contains("\"occurredAt\":\"2026-10-19T08:00:00Z\"")
                    .doesNotContain("@class");
        });
    }

    @Test
    void turnsAnEnvelopeWithoutTraceIdIntoADeserializationError() {
        contextRunner.run(context -> {
            Headers headers = new RecordHeaders();
            byte[] json = """
                    {"eventId":"01936b2a-7c4e-7000-8000-000000000001","eventType":"ProductCreated",
                     "aggregateId":"product-1","occurredAt":"2026-10-19T08:00:00Z","payload":{}}
                    """.getBytes(StandardCharsets.UTF_8);

            Object received = deserializer(context).deserialize(Topics.CATALOG, headers, json);

            assertThat(received).isNull();
            assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
        });
    }

    @SuppressWarnings("unchecked")
    private static Serializer<EventEnvelope<?>> serializer(ApplicationContext context) {
        KafkaTemplate<String, EventEnvelope<?>> template = context.getBean(EventKafka.TEMPLATE, KafkaTemplate.class);
        return template.getProducerFactory().getValueSerializerSupplier().get();
    }

    private static Deserializer<?> deserializer(ApplicationContext context) {
        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                context.getBean(EventKafka.LISTENER_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
        return factory.getConsumerFactory().getValueDeserializer();
    }
}
