package com.group5.paymentservice.shared.config;

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
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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

/** Kiểm tra cách chọn Kafka key của Payment và giữ nguyên envelope khi serialize. */
class PaymentOutboxConfigurationTest {
    private final JsonMapper mapper = JsonMapper.shared();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    /** Auto-config phải truyền group key cho relay, còn aggregate và metadata vẫn giữ giá trị đã lưu. */
    @Test
    @SuppressWarnings("unchecked")
    void autoConfiguredRelaySendsGroupKeyWhilePreservingSessionAggregateAndEnvelopeOnTheWire() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        KafkaTemplate<String, EventEnvelope<?>> kafka = mock(KafkaTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        EventEnvelope<JsonNode> event = event("CheckoutPaymentConfirmed", Map.of("orderGroupId", groupId));
        OutboxEntry row = new OutboxEntry(event.eventId(), "PaymentSession", sessionId.toString(),
                event.eventType(), 1, Topics.PAYMENT, mapper.writeValueAsString(event), event.traceId(),
                event.occurredAt(), null);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(row));
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .withUserConfiguration(PaymentOutboxConfiguration.class)
                .withPropertyValues("nexus.outbox.relay.initial-delay=3600000")
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .withBean(EventKafka.TEMPLATE, KafkaTemplate.class, () -> kafka)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OutboxEventKeyResolver.class);
                    context.getBean(OutboxRelay.class).publishPending();
                });

        ArgumentCaptor<EventEnvelope<?>> sent = ArgumentCaptor.captor();
        verify(kafka).send(eq(Topics.PAYMENT), eq(groupId.toString()), sent.capture());
        try (JacksonJsonSerializer<EventEnvelope<?>> serializer = new JacksonJsonSerializer<>(mapper)) {
            serializer.setAddTypeInfo(false);
            JsonNode wire = mapper.readTree(serializer.serialize(Topics.PAYMENT, sent.getValue()));
            assertThat(wire.get("aggregateId").asString()).isEqualTo(sessionId.toString());
            assertThat(wire.get("payload").get("orderGroupId").asString()).isEqualTo(groupId.toString());
            assertThat(wire.get("eventId").asString()).isEqualTo(event.eventId().toString());
            assertThat(wire.get("traceId").asString()).isEqualTo(event.traceId());
            assertThat(wire.get("occurredAt").asString()).isEqualTo(event.occurredAt().toString());
        }
        verify(jdbc).update(anyString(), eq(event.eventId()));
    }

    /** Chỉ confirmation đổi key; event thanh toán khác giữ hành vi theo aggregate. */
    @Test
    void otherPaymentEventsKeepTheirAggregateKey() {
        OutboxEventKeyResolver resolver = new PaymentOutboxConfiguration().paymentOutboxEventKeyResolver();
        assertThat(resolver.resolve(event("OrderRefundRequested", Map.of("orderGroupId", groupId))))
                .isEqualTo(sessionId.toString());
    }

    /** Không tự dùng session làm key khi confirmation thiếu hoặc sai orderGroupId. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"orderGroupId\":null}", "{\"orderGroupId\":5}",
            "{\"orderGroupId\":\"not-a-uuid\"}", "{\"orderGroupId\":\"1-1-1-1-1\"}"})
    void missingOrMalformedRequiredGroupKeyFailsInsteadOfFallingBackToSession(String payload) {
        OutboxEventKeyResolver resolver = new PaymentOutboxConfiguration().paymentOutboxEventKeyResolver();
        EventEnvelope<JsonNode> event = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentConfirmed",
                sessionId.toString(), Instant.parse("2026-11-10T08:00:00Z"), "trace156", mapper.readTree(payload));
        assertThatThrownBy(() -> resolver.resolve(event)).isInstanceOf(IllegalArgumentException.class);
    }

    private EventEnvelope<JsonNode> event(String type, Map<String, Object> payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, sessionId.toString(),
                Instant.parse("2026-11-10T08:00:00Z"), "trace156", mapper.valueToTree(payload));
    }
}
