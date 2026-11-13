package com.group5.shipmentservice.shared.config;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxEntry;
import com.group5.common_outbox.OutboxEventKeyResolver;
import com.group5.common_outbox.OutboxRelay;
import com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShipmentOutboxConfigurationTest {
    private final JsonMapper mapper = JsonMapper.shared();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EventEnvelope<?>> kafka = mock(KafkaTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final OutboxEventKeyResolver resolver = new ShipmentOutboxConfiguration().shipmentOutboxEventKeyResolver();

    @ParameterizedTest
    @ValueSource(strings = {"ShipmentCreated", "ShipmentPickedUp", "ShipmentInTransit", "ShipmentDelivered",
            "ShipmentFailed", "ShipmentReturned", "ShipmentCancelled", "ShipmentStatusUpdated"})
    @SuppressWarnings("unchecked")
    void autoConfiguredRelayPublishesByOrderIdWhilePreservingShipmentAggregateAndWireEnvelope(String eventType) {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        EventEnvelope<?> event = new EventEnvelope<>(UUID.randomUUID(), eventType, shipmentId.toString(),
                Instant.parse("2026-11-11T00:00:00Z"), "trace-1", Map.of("orderId", orderId.toString()));
        OutboxEntry entry = entry(Topics.SHIPMENT, event);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(entry));
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        contextRunner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OutboxRelay.class)
                    .hasSingleBean(OutboxEventKeyResolver.class);
            assertThat(context.getBean(OutboxRelay.class).getClass()).isEqualTo(OutboxRelay.class);
            context.getBean(OutboxRelay.class).publishPending();
        });

        var sent = org.mockito.ArgumentCaptor.<EventEnvelope<?>>captor();
        verify(kafka).send(eq(Topics.SHIPMENT), eq(orderId.toString()), sent.capture());
        assertThat(sent.getValue().aggregateId()).isEqualTo(shipmentId.toString());
        try (var serializer = new JacksonJsonSerializer<EventEnvelope<?>>(mapper)) {
            serializer.setAddTypeInfo(false);
            var wire = mapper.readTree(serializer.serialize(Topics.SHIPMENT, sent.getValue()));
            assertThat(wire.path("aggregateId").asString()).isEqualTo(shipmentId.toString());
            assertThat(wire.path("payload").path("orderId").asString()).isEqualTo(orderId.toString());
            assertThat(wire.path("eventId").asString()).isEqualTo(event.eventId().toString());
            assertThat(wire.path("traceId").asString()).isEqualTo(event.traceId());
            assertThat(wire.path("occurredAt").asString()).isEqualTo(event.occurredAt().toString());
        }
        verify(jdbc).update(anyString(), eq(event.eventId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"orderId\":null}", "{\"orderId\":5}",
            "{\"orderId\":\"invalid\"}", "{\"orderId\":\"1-1-1-1-1\"}"})
    void rejectsMissingAndMalformedOrderKeysWithoutFallingBackToShipmentId(String payload) {
        var event = new EventEnvelope<>(UUID.randomUUID(), "ShipmentCreated", UUID.randomUUID().toString(),
                Instant.now(), "trace-1", mapper.readTree(payload));
        assertThatThrownBy(() -> resolver.resolve(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesDefaultAggregateKeyForOtherEvents() {
        var event = new EventEnvelope<JsonNode>(UUID.randomUUID(), "OtherEvent", "aggregate-1", Instant.now(),
                "trace-1", mapper.valueToTree(Map.of("orderId", UUID.randomUUID())));
        assertThat(resolver.resolve(event)).isEqualTo("aggregate-1");
    }

    @Test
    void disabledRelayDoesNotCreateAScheduledPublisher() {
        contextRunner()
                .withPropertyValues("nexus.outbox.relay.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxRelay.class)
                        .hasSingleBean(OutboxEventKeyResolver.class));
    }

    @SuppressWarnings("unchecked")
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .withUserConfiguration(ShipmentOutboxConfiguration.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .withBean(EventKafka.TEMPLATE, KafkaTemplate.class, () -> kafka)
                .withPropertyValues("nexus.outbox.relay.initial-delay=3600000");
    }

    private OutboxEntry entry(String topic, EventEnvelope<?> event) {
        return new OutboxEntry(event.eventId(), "Shipment", event.aggregateId(), event.eventType(), 1, topic,
                mapper.writeValueAsString(event), event.traceId(), event.occurredAt(), null);
    }
}
