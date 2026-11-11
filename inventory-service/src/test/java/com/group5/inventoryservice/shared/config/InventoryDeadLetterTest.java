package com.group5.inventoryservice.shared.config;

import com.group5.common_events.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryDeadLetterTest {
    private final InventoryKafkaConfiguration configuration = new InventoryKafkaConfiguration();
    private final JsonMapper mapper = JsonMapper.shared();

    @Test
    void aFailedEventIsPublishedToTheInventoryDeadLetterTopicAsJsonWithItsSource() {
        Fixture fixture = new Fixture();
        UUID order = UUID.randomUUID();
        EventEnvelope<JsonNode> event = new EventEnvelope<>(UUID.randomUUID(), "OrderPaid", order.toString(),
                Instant.parse("2026-11-11T03:00:00Z"), "trace-dlt", mapper.readTree("{\"orderId\":\"" + order + "\"}"));
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("nexus.commerce.events", 0, 7L,
                order.toString(), event);

        fixture.recoverer.accept(record, null, new IllegalStateException("reservation cannot be confirmed"));

        var published = fixture.producer.history().getFirst();
        assertThat(published.topic()).isEqualTo("nexus.inventory.dlq");
        assertThat(published.key()).isEqualTo(order.toString());
        byte[] json = fixture.factory.getValueSerializer().serialize(published.topic(), published.value());
        assertThat(mapper.readTree(json).get("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(text(published.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value()))
                .isEqualTo("nexus.commerce.events");
        assertThat(text(published.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE).value()))
                .contains("reservation cannot be confirmed");
    }

    @Test
    void aDeadLetterThatCannotBeWrittenThrowsSoTheOffsetIsNotCommitted() {
        Fixture fixture = new Fixture();
        fixture.producer.sendException = new KafkaException("dead letter topic unavailable");
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("nexus.commerce.events", 0, 7L, "key",
                new byte[] {1, 2});

        assertThatThrownBy(() -> fixture.recoverer.accept(record, null, new IllegalStateException("failed")))
                .isInstanceOf(org.springframework.kafka.KafkaException.class);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private final class Fixture {
        private final DefaultKafkaProducerFactory<String, Object> factory =
                configuration.inventoryDeadLetterProducerFactory(new KafkaProperties(),
                        new StaticListableBeanFactory().getBeanProvider(JsonMapper.class));
        private final MockProducer<String, Object> producer = new MockProducer<>(true, null,
                new StringSerializer(), factory.getValueSerializer());
        private final DeadLetterPublishingRecoverer recoverer = configuration.inventoryDeadLetterRecoverer(
                new KafkaTemplate<>(new MockProducerFactory<>(() -> producer)));
    }
}
