package com.group5.notificationservice.notification;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.RETURNS_SELF;

class DomainEventConsumerTest {

    private static final UUID BUYER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private final IdempotentConsumer idempotentConsumer = mock(IdempotentConsumer.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationChannel channel = mock(NotificationChannel.class);
    private final DomainEventConsumer consumer =
            new DomainEventConsumer(idempotentConsumer, repository, List.of(channel), "https://nexus.example");

    @Test
    void orderCreatedSavesOneNotificationForTheBuyer() {
        runHandlerOnFirstDelivery();
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        consumer.receive(record(Topics.COMMERCE, "OrderCreated", """
                {"orderId":"00000000-0000-4000-8000-000000000001","buyerId":"%s",
                 "orderNumber":"NX-20261120-0001","orderType":"STANDARD","referenceId":null}
                """.formatted(BUYER_ID)));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(BUYER_ID);
        assertThat(saved.getValue().getContent()).contains("NX-20261120-0001");
        verify(channel).send(saved.getValue());
    }

    @Test
    void anAlreadyHandledEventSavesNothing() {
        when(idempotentConsumer.handle(eq(DomainEventConsumer.CONSUMER), any(), any())).thenReturn(false);

        consumer.receive(record(Topics.COMMERCE, "OrderCreated", """
                {"orderId":"00000000-0000-4000-8000-000000000001","buyerId":"%s",
                 "orderNumber":"NX-20261120-0001","orderType":"STANDARD","referenceId":null}
                """.formatted(BUYER_ID)));

        verify(repository, never()).save(any());
    }

    @Test
    void categoryCreatedIsSkippedWithoutTouchingTheDatabase() {
        consumer.receive(record(Topics.CATALOG, "CategoryCreated", """
                {"categoryId":"00000000-0000-4000-8000-000000000007","name":"Electronics",
                 "slug":"electronics","parentId":null}
                """));

        verifyNoInteractions(idempotentConsumer, repository, channel);
    }

    @Test
    void anEventTypeWithoutATemplateIsSkipped() {
        consumer.receive(record(Topics.COMMERCE, "OrderPaid", "{\"orderId\":\"00000000-0000-4000-8000-000000000001\"}"));

        verifyNoInteractions(idempotentConsumer, repository, channel);
    }

    @Test
    void listenerIncludesTheActualPaymentShipmentAndAuctionTopics() throws Exception {
        // Kiểm tra cấu hình listener thật: gọi receive trực tiếp không phát hiện thiếu subscription.
        KafkaListener listener = DomainEventConsumer.class.getMethod("receive", ConsumerRecord.class)
                .getAnnotation(KafkaListener.class);
        assertThat(listener.topics()).contains(Topics.USER, Topics.COMMERCE, Topics.CATALOG, Topics.PAYMENT,
                Topics.SHIPMENT, Topics.AUCTION);
    }

    @Test
    void shipmentDeliveredCreatesTheBuyerNotificationWithRatingLink() {
        // Nội dung lấy orderId từ event shipment, không gửi nhầm cho kho hoặc seller.
        runHandlerOnFirstDelivery();
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
        consumer.receive(record(Topics.SHIPMENT, "ShipmentDelivered", """
                {"orderId":"00000000-0000-4000-8000-000000000001","buyerId":"%s"}
                """.formatted(BUYER_ID)));
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(BUYER_ID);
        assertThat(saved.getValue().getContent()).contains("https://nexus.example/order/");
    }

    @Test
    void cancelledAuctionFansOutToDistinctBiddersAndSeller() {
        // Nhiều bid của cùng người vẫn chỉ tạo một notification và một lần gọi channel.
        runHandlerOnFirstDelivery();
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
        consumer.receive(record(Topics.AUCTION, "AuctionCancelled", """
                {"auctionId":"auction-259","reason":"Policy violation","bidderIds":["%s","%s"],
                 "sellerId":"00000000-0000-4000-8000-000000000004"}
                """.formatted(BUYER_ID, BUYER_ID)));
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Notification::getUserId).containsExactly(
                BUYER_ID, UUID.fromString("00000000-0000-4000-8000-000000000004"));
        verify(channel, times(2)).send(any());
    }

    @ParameterizedTest
    @MethodSource("historicalRefundEvents")
    void historicalRefundReplayIsProcessedWithoutGuessingABuyerAndTheNextEventContinues(
            String eventType, boolean explicitNull) {
        Set<UUID> processed = new HashSet<>();
        DomainEventConsumer replayConsumer = consumerWithRealIdempotency(processed);
        ConsumerRecord<String, EventEnvelope<JsonNode>> historical = record(Topics.PAYMENT, eventType,
                historicalRefundPayload(eventType, explicitNull));

        replayConsumer.receive(historical);
        replayConsumer.receive(historical);

        assertThat(processed).containsExactly(historical.value().eventId());
        verifyNoInteractions(repository, channel);

        // Offset sau payload v1 vẫn đi tiếp; actor của refund cũ không bị dùng làm buyer.
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ConsumerRecord<String, EventEnvelope<JsonNode>> next = record(Topics.PAYMENT, "CheckoutPaymentConfirmed", """
                {"buyerId":"%s","orderGroupId":"00000000-0000-4000-8000-000000000011",
                 "totalAmount":"50000.00","currency":"VND"}
                """.formatted(BUYER_ID));
        replayConsumer.receive(next);
        replayConsumer.receive(next);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(BUYER_ID);
        assertThat(saved.getValue().getType()).isEqualTo("CHECKOUT_PAYMENT_CONFIRMED");
        verify(channel).send(saved.getValue());
        assertThat(processed).containsExactlyInAnyOrder(historical.value().eventId(), next.value().eventId());
    }

    static Stream<Arguments> historicalRefundEvents() {
        return Stream.of("OrderRefundRequested", "OrderRefundCompleted")
                .flatMap(type -> Stream.of(Arguments.of(type, false), Arguments.of(type, true)));
    }

    @ParameterizedTest
    @MethodSource("historicalBidderSnapshots")
    void historicalCancelledAuctionReplayNotifiesTheKnownSellerOnce(boolean explicitNull) {
        Set<UUID> processed = new HashSet<>();
        DomainEventConsumer replayConsumer = consumerWithRealIdempotency(processed);
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ConsumerRecord<String, EventEnvelope<JsonNode>> historical = record(Topics.AUCTION, "AuctionCancelled", """
                {"auctionId":"00000000-0000-4000-8000-000000000020",
                 "sellerId":"00000000-0000-4000-8000-000000000004",
                 "productId":"00000000-0000-4000-8000-000000000021",
                 "skuId":"00000000-0000-4000-8000-000000000022",
                 "actorId":"00000000-0000-4000-8000-000000000003","cancelledBy":"ADMIN",
                 "reason":"Policy violation","auctionVersion":2,"cancelledAt":"2026-11-16T10:00:00Z"%s}
                """.formatted(explicitNull ? ",\"bidderIds\":null" : ""));

        replayConsumer.receive(historical);
        replayConsumer.receive(historical);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId())
                .isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000004"));
        assertThat(saved.getValue().getContent()).contains("Policy violation");
        verify(channel).send(saved.getValue());
        assertThat(processed).containsExactly(historical.value().eventId());
    }

    static Stream<Boolean> historicalBidderSnapshots() {
        return Stream.of(false, true);
    }

    @Test
    void malformedRefundBuyerRemainsRetryableRatherThanBeingTreatedAsHistorical() {
        Set<UUID> processed = new HashSet<>();
        DomainEventConsumer replayConsumer = consumerWithRealIdempotency(processed);
        var payload = (tools.jackson.databind.node.ObjectNode) JsonMapper.shared()
                .readTree(historicalRefundPayload("OrderRefundRequested", false));
        payload.put("buyerId", "invalid-uuid");
        ConsumerRecord<String, EventEnvelope<JsonNode>> malformed = record(Topics.PAYMENT,
                "OrderRefundRequested", payload.toString());

        assertThatThrownBy(() -> replayConsumer.receive(malformed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayConsumer.receive(malformed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(processed).isEmpty();
        verifyNoInteractions(repository, channel);
    }

    private static String historicalRefundPayload(String eventType, boolean explicitNull) {
        return """
                {"refundId":"00000000-0000-4000-8000-000000000010",
                 "orderId":"00000000-0000-4000-8000-000000000001",
                 "orderGroupId":"00000000-0000-4000-8000-000000000011",
                 "sessionId":"00000000-0000-4000-8000-000000000012",
                 "instructionId":"00000000-0000-4000-8000-000000000013",
                 "incomingTransactionId":"00000000-0000-4000-8000-000000000014",
                 "amount":"50000.00","currency":"VND","status":"%s","reason":"Damaged",
                 "actorId":"00000000-0000-4000-8000-000000000003","refundVersion":1,"instructionVersion":2,
                 "requestedAt":"2026-11-16T09:00:00Z",
                 "completedTransactionId":"00000000-0000-4000-8000-000000000015",
                 "completedAt":"2026-11-16T10:00:00Z"%s}
                """.formatted(eventType.equals("OrderRefundCompleted") ? "COMPLETED" : "REQUESTED",
                explicitNull ? ",\"buyerId\":null" : "");
    }

    @SuppressWarnings("unchecked")
    private DomainEventConsumer consumerWithRealIdempotency(Set<UUID> processed) {
        // Chạy IdempotentConsumer thật; chỉ mô phỏng claim/transaction để unit test không cần Docker.
        EntityManager entityManager = mock(EntityManager.class);
        Query claim = mock(Query.class, RETURNS_SELF);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        UUID[] claimingEvent = new UUID[1];
        when(entityManager.createNativeQuery(anyString())).thenReturn(claim);
        when(claim.setParameter(eq("eventId"), any())).thenAnswer(invocation -> {
            claimingEvent[0] = invocation.getArgument(1);
            return claim;
        });
        when(claim.executeUpdate()).thenAnswer(invocation -> processed.add(claimingEvent[0]) ? 1 : 0);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            Set<UUID> before = new HashSet<>(processed);
            TransactionCallback<Object> action = invocation.getArgument(0);
            try {
                return action.doInTransaction(mock(TransactionStatus.class));
            } catch (RuntimeException failure) {
                processed.clear();
                processed.addAll(before);
                throw failure;
            }
        });
        return new DomainEventConsumer(new IdempotentConsumer(entityManager, transactions), repository,
                List.of(channel), "https://nexus.example");
    }

    @SuppressWarnings("unchecked")
    private void runHandlerOnFirstDelivery() {
        when(idempotentConsumer.handle(eq(DomainEventConsumer.CONSUMER), any(), any())).thenAnswer(invocation -> {
            Consumer<EventEnvelope<JsonNode>> handler = invocation.getArgument(2);
            handler.accept(invocation.getArgument(1));
            return true;
        });
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> record(String topic, String eventType, String payload) {
        UUID aggregateId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, aggregateId.toString(),
                Instant.parse("2026-11-20T10:00:00Z"), "4bf92f3577b34da6a3ce929d0e0e4736",
                JsonMapper.shared().readTree(payload));
        return new ConsumerRecord<>(topic, 0, 0L, aggregateId.toString(), envelope);
    }
}
