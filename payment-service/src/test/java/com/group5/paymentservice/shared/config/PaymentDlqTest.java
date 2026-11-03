package com.group5.paymentservice.shared.config;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentDlqTest {
    private final PaymentConfiguration configuration = new PaymentConfiguration();
    private final JsonMapper mapper = JsonMapper.shared();

    @Test
    void malformedJsonIsPublishedAsExactBytesWithErrorAndSourceMetadata() {
        try (var fixture = new Fixture()) {
            byte[] raw = "{broken-json".getBytes(StandardCharsets.UTF_8);
            var record = new ConsumerRecord<String, EventEnvelope<JsonNode>>(
                    "nexus.commerce", 2, 42L, "group-123", null);
            record.headers().add("correlation-id", "trace-123".getBytes(StandardCharsets.UTF_8));
            var type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, JsonNode.class);
            try (var deserializer = new ErrorHandlingDeserializer<>(
                    new JacksonJsonDeserializer<EventEnvelope<JsonNode>>(type, mapper, false))) {
                assertThat(deserializer.deserialize(record.topic(), record.headers(), raw)).isNull();
            }
            var failure = SerializationUtils.getExceptionFromHeader(record,
                    SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, new LogAccessor(PaymentDlqTest.class));
            assertThat(failure).isNotNull();
            fixture.recoverer.accept(record, null, failure);

            assertThat(fixture.producer.history()).hasSize(1);
            var published = fixture.producer.history().getFirst();
            assertThat(published.topic()).isEqualTo("nexus.payment.dlq");
            assertThat(published.key()).isEqualTo(record.key());
            assertThat(published.value()).isEqualTo(raw);
            // Exercise the actual production serializer: bytes must not become Base64 JSON.
            assertThat(fixture.factory.getValueSerializer().serialize(published.topic(), published.value()))
                    .isEqualTo(raw);
            assertThat(text(published.headers(), KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(record.topic());
            assertThat(ByteBuffer.wrap(published.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value())
                    .getInt()).isEqualTo(2);
            assertThat(ByteBuffer.wrap(published.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value())
                    .getLong()).isEqualTo(42L);
            assertThat(text(published.headers(), KafkaHeaders.DLT_EXCEPTION_FQCN))
                    .contains("DeserializationException");
            assertThat(text(published.headers(), KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNotBlank();
            assertThat(text(published.headers(), "correlation-id")).isEqualTo("trace-123");
        }
    }

    @Test
    void listenerFailureRetainsTheEnvelopeAndSerializesItAsJson() {
        try (var fixture = new Fixture()) {
            var event = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentRequested", "group-123",
                    Instant.parse("2026-11-02T00:00:00Z"), "trace-123", Map.of("amount", 350000));
            var record = new ConsumerRecord<>("nexus.commerce", 0, 7L, "group-123", event);
            fixture.recoverer.accept(record, null, new IllegalArgumentException("Invalid payment manifest"));

            var published = fixture.producer.history().getFirst();
            assertThat(published.value()).isEqualTo(event);
            byte[] json = fixture.factory.getValueSerializer().serialize(published.topic(), published.value());
            assertThat(mapper.readTree(json).get("eventId").asString()).isEqualTo(event.eventId().toString());
            assertThat(text(published.headers(), KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                    .contains("Invalid payment manifest");
        }
    }

    @Test
    void failedDlqPublishThrowsSoTheRecordCanBeRetried() {
        try (var fixture = new Fixture()) {
            fixture.producer.sendException = new KafkaException("DLQ unavailable");
            var record = new ConsumerRecord<>("nexus.commerce", 0, 7L, "group-123", new byte[] {1, 2});
            assertThatThrownBy(() -> fixture.recoverer.accept(record, null, new IllegalArgumentException("bad event")))
                    .isInstanceOf(org.springframework.kafka.KafkaException.class);
        }
    }

    @Test
    void paymentKafkaBeansStartTogetherWithTheSharedListenerFactory() {
        new ApplicationContextRunner().withUserConfiguration(PaymentConfiguration.class)
                .withBean(KafkaProperties.class, KafkaProperties::new)
                .withBean(PaymentProperties.class, () -> new PaymentProperties("SEPAY", 3))
                .withBean(EventKafka.LISTENER_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class,
                        ConcurrentKafkaListenerContainerFactory::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeadLetterPublishingRecoverer.class);
                    assertThat(context).hasBean("paymentEventErrorHandler");
                });
    }

    private static String text(Headers headers, String key) {
        assertThat(headers.lastHeader(key)).as("header %s", key).isNotNull();
        return new String(headers.lastHeader(key).value(), StandardCharsets.UTF_8);
    }

    private class Fixture implements AutoCloseable {
        final DefaultKafkaProducerFactory<String, Object> factory = configuration.paymentDlqProducerFactory(
                new KafkaProperties(), new StaticListableBeanFactory().getBeanProvider(JsonMapper.class));
        final MockProducer<String, Object> producer = new MockProducer<>(true, null,
                new StringSerializer(), factory.getValueSerializer());
        final KafkaTemplate<String, Object> template = new KafkaTemplate<>(new MockProducerFactory<>(() -> producer));
        final DeadLetterPublishingRecoverer recoverer = configuration.paymentDeadLetterRecoverer(template);

        @Override
        public void close() {
            template.destroy();
            producer.close();
            factory.destroy();
        }
    }
}
