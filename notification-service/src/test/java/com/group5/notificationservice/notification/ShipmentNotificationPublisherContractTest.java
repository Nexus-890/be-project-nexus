package com.group5.notificationservice.notification;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShipmentNotificationPublisherContractTest {

    @Test
    void realPublisherPayloadWaitsForPickupAndReplaysWithoutAnotherNotification() throws Exception {
        // Fixture này được ShipmentNotificationPublisherTest so khớp với outbox từ service thật.
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/shipment-notification-publisher.json")) {
            assertThat(input).isNotNull();
            fixture = JsonMapper.shared().readTree(input);
        }
        EntityManager entityManager = mock(EntityManager.class);
        Query claim = mock(Query.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(entityManager.createNativeQuery(anyString())).thenReturn(claim);
        AtomicReference<UUID> claimedId = new AtomicReference<>();
        Set<UUID> processed = new HashSet<>();
        when(claim.setParameter(eq("eventId"), any())).thenAnswer(call -> {
            claimedId.set(call.getArgument(1));
            return claim;
        });
        when(claim.setParameter("consumer", "notification-domain-events")).thenReturn(claim);
        // Mô phỏng UNIQUE ở biên JDBC; đây là unit test, không chứng nhận transaction PostgreSQL.
        when(claim.executeUpdate()).thenAnswer(call -> processed.add(claimedId.get()) ? 1 : 0);
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationChannel channel = mock(NotificationChannel.class);
        when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
        DomainEventConsumer consumer = new DomainEventConsumer(
                new IdempotentConsumer(entityManager, new TransactionTemplate(transactions)),
                repository, List.of(channel), "https://nexus.example");

        consumer.receive(record(fixture.path("created")));
        verifyNoInteractions(entityManager, repository, channel);

        var pickedUp = record(fixture.path("pickedUp"));
        consumer.receive(pickedUp);
        consumer.receive(pickedUp);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        JsonNode payload = pickedUp.value().payload();
        assertThat(saved.getValue().getUserId()).isEqualTo(UUID.fromString(payload.path("buyerId").asString()));
        // Type lưu trong DB giữ nguyên để preference tắt email shipment trước đây tiếp tục áp dụng.
        assertThat(saved.getValue().getType()).isEqualTo("SHIPMENT_CREATED");
        assertThat(saved.getValue().getTitle()).isEqualTo("Order shipped");
        assertThat(saved.getValue().getContent()).contains(payload.path("orderId").asString(),
                payload.path("carrier").asString(), payload.path("trackingCode").asString())
                .doesNotContain("Pending carrier confirmation");
        verify(channel).send(saved.getValue());
        assertThat(processed).containsExactly(pickedUp.value().eventId());
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> record(JsonNode event) {
        JsonNode payload = event.path("payload");
        var envelope = new EventEnvelope<>(UUID.randomUUID(), event.path("eventType").asString(),
                payload.path("shipmentId").asString(), Instant.parse("2026-11-16T10:00:00Z"),
                "4bf92f3577b34da6a3ce929d0e0e4736", payload);
        return new ConsumerRecord<>(Topics.SHIPMENT, 0, 0L, payload.path("orderId").asString(), envelope);
    }
}
