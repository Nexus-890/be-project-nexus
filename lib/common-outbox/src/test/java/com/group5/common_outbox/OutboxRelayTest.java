package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EventEnvelope<?>> kafka = mock(KafkaTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JsonMapper mapper = JsonMapper.shared();
    private final OutboxRelay relay = new OutboxRelay(jdbc, mapper, kafka, transactions, Duration.ofMillis(50));
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final EventEnvelope<?> event = new EventEnvelope<>(UUID.randomUUID(), "ProductCreated", "product-1",
            Instant.parse("2026-10-20T08:00:00Z"), "4bf92f3577b34da6a3ce929d0e0e4736", Map.of("name", "Phone"));

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(status);
    }

    @Test
    void claimsAndMarksAnAcknowledgedEventInTheSameTransaction() {
        rows(entry(event));
        acknowledge();

        relay.publishPending();

        var order = inOrder(transactions, jdbc, kafka);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        order.verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        order.verify(jdbc).query(eq(OutboxRelay.CLAIM_SQL), any(RowMapper.class));
        ArgumentCaptor<EventEnvelope<?>> sent = ArgumentCaptor.captor();
        order.verify(kafka).send(eq(Topics.CATALOG), eq(event.aggregateId()), sent.capture());
        order.verify(jdbc).update(OutboxRelay.MARK_PUBLISHED_SQL, event.eventId());
        order.verify(transactions).commit(status);
        assertThat(sent.getValue().eventId()).isEqualTo(event.eventId());
        assertThat(sent.getValue().occurredAt()).isEqualTo(event.occurredAt());
        assertThat(sent.getValue().traceId()).isEqualTo(event.traceId());
        assertThat(sent.getValue().payload()).isInstanceOf(JsonNode.class);
    }

    @Test
    void doesNotMarkAFailedPublish() {
        rows(entry(event));
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        relay.publishPending();

        verify(jdbc, never()).update(anyString(), any(UUID.class));
        verify(transactions).commit(status);
    }

    @Test
    void doesNotMarkAnUnacknowledgedSendOnTimeout() {
        rows(entry(event));
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class))).thenReturn(new CompletableFuture<>());

        relay.publishPending();

        verify(jdbc, never()).update(anyString(), any(UUID.class));
    }

    @Test
    void keepsEarlierSuccessesWhenALaterPublishFails() {
        EventEnvelope<?> second = new EventEnvelope<>(UUID.randomUUID(), "ProductUpdated", "product-2",
                event.occurredAt(), event.traceId(), Map.of("name", "Other"));
        rows(entry(event), entry(second));
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        relay.publishPending();

        verify(jdbc).update(OutboxRelay.MARK_PUBLISHED_SQL, event.eventId());
        verify(jdbc, never()).update(OutboxRelay.MARK_PUBLISHED_SQL, second.eventId());
        verify(transactions).commit(status);
    }

    @Test
    void rollsBackOnADatabaseFailureRatherThanHidingItAsAPublishFailure() {
        rows(entry(event));
        acknowledge();
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(jdbc).update(OutboxRelay.MARK_PUBLISHED_SQL, event.eventId());

        assertThatThrownBy(relay::publishPending).isInstanceOf(DataAccessResourceFailureException.class);

        verify(transactions).rollback(status);
        verify(transactions, never()).commit(status);
    }

    @Test
    void restoresTheInterruptFlagAndLeavesTheRowUnpublished() {
        rows(entry(event));
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class))).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            relay.publishPending();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(jdbc, never()).update(anyString(), any(UUID.class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNotContactKafkaWhenThereAreNoRows() {
        rows();

        relay.publishPending();

        verifyNoInteractions(kafka);
    }

    @Test
    void rejectsInvalidTimeouts() {
        assertThatThrownBy(() -> new OutboxRelay(jdbc, mapper, kafka, transactions, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private void rows(OutboxEntry... entries) {
        when(jdbc.query(eq(OutboxRelay.CLAIM_SQL), any(RowMapper.class))).thenReturn(List.of(entries));
    }

    @SuppressWarnings("unchecked")
    private void acknowledge() {
        when(kafka.send(anyString(), anyString(), any(EventEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private OutboxEntry entry(EventEnvelope<?> envelope) {
        return new OutboxEntry(envelope.eventId(), "Product", envelope.aggregateId(), envelope.eventType(), 1,
                Topics.CATALOG, mapper.writeValueAsString(envelope), envelope.traceId(), envelope.occurredAt(), null);
    }
}
