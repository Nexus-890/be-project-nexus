package com.group5.shipmentservice.shared.config;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxEntry;
import com.group5.common_outbox.OutboxRelay;
import com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
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
    private final ShipmentOutboxConfiguration.ShipmentRelay relay = new ShipmentOutboxConfiguration.ShipmentRelay(
            jdbc, mapper, kafka, transactions, Duration.ofSeconds(1));

    @Test
    @SuppressWarnings("unchecked")
    void publishesByOrderIdWhilePreservingShipmentAggregate() {
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        EventEnvelope<?> event = new EventEnvelope<>(UUID.randomUUID(), "ShipmentCreated", shipmentId.toString(),
                Instant.parse("2026-11-11T00:00:00Z"), "trace-1", Map.of("orderId", orderId.toString()));
        OutboxEntry entry = entry(Topics.SHIPMENT, event);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(entry));
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        relay.publishPending();

        var sent = org.mockito.ArgumentCaptor.<EventEnvelope<?>>captor();
        verify(kafka).send(eq(Topics.SHIPMENT), eq(orderId.toString()), sent.capture());
        assertThat(sent.getValue().aggregateId()).isEqualTo(shipmentId.toString());
        verify(jdbc).update(anyString(), eq(event.eventId()));
    }

    @Test
    void rejectsMissingAndMalformedOrderKeysWithoutFallingBackToShipmentId() {
        for (Map<String, String> payload : List.of(Map.<String, String>of(), Map.of("orderId", "invalid"))) {
            EventEnvelope<?> event = new EventEnvelope<>(UUID.randomUUID(), "ShipmentCreated", UUID.randomUUID().toString(),
                    Instant.now(), "trace-1", mapper.valueToTree(payload));
            assertThatThrownBy(() -> relay.publicationKey(entry(Topics.SHIPMENT, event), event))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void preservesDefaultAggregateKeyForOtherTopics() {
        EventEnvelope<?> event = new EventEnvelope<>(UUID.randomUUID(), "OtherEvent", "aggregate-1", Instant.now(),
                "trace-1", Map.of("value", "something"));
        assertThat(relay.publicationKey(entry(Topics.COMMERCE, event), event)).isEqualTo("aggregate-1");
    }

    @Test
    void disabledRelayDoesNotCreateAScheduledPublisher() {
        new ApplicationContextRunner().withUserConfiguration(ShipmentOutboxConfiguration.class)
                .withPropertyValues("nexus.outbox.relay.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxRelay.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void domainRelayReplacesTheGenericRelayWhenAutoConfigurationRuns() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .withUserConfiguration(ShipmentOutboxConfiguration.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .withBean(EventKafka.TEMPLATE, KafkaTemplate.class, () -> kafka)
                .withPropertyValues("nexus.outbox.relay.initial-delay=3600000")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OutboxRelay.class);
                    assertThat(context.getBean(OutboxRelay.class))
                            .isInstanceOf(ShipmentOutboxConfiguration.ShipmentRelay.class);
                });
    }

    private OutboxEntry entry(String topic, EventEnvelope<?> event) {
        return new OutboxEntry(event.eventId(), "Shipment", event.aggregateId(), event.eventType(), 1, topic,
                mapper.writeValueAsString(event), event.traceId(), event.occurredAt(), null);
    }
}
