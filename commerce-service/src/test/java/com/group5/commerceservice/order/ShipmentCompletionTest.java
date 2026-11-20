package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderCompleted;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.CurrentUser;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ShipmentCompletionTest.JpaConfig.class)
class ShipmentCompletionTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.commerceservice.PostgresTestDatabase("postgres:18-alpine");
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();
    private static final UUID SYSTEM = new UUID(0, 0);

    static { POSTGRES.start(); }

    @Autowired private ShipmentEventConsumer listener;
    @Autowired private OrderEventPublisher publisher;
    @Autowired private DataSource dataSource;
    @Autowired private OrderReceiptService receipts;
    @Autowired private OrderCompletionService completions;
    @Autowired private OrderCompletionJob autoCompletion;
    @Autowired private PendingShipmentEvents pending;
    @Autowired private ShipmentRetryJob retries;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ShipmentStatusHandler handler;
    @Autowired private Tracer tracer;
    @Autowired private Clock clock;

    @BeforeEach
    void resetPublisher() {
        reset(publisher);
        jdbc().update("DELETE FROM pending_order_shipment_events");
    }

    @Test
    void pickupAndDeliveryWaitForBuyerConfirmationBeforePublishingThePersistedSnapshot() {
        Fixture order = order(OrderStatus.PAID, OrderType.STANDARD);
        jdbc().update("UPDATE orders SET shipping_fee = 25000.00, total_amount = 325000.00 WHERE id = ?", order.id());
        send(event(order, "ShipmentPickedUp"));
        assertThat(status(order)).isEqualTo("SHIPPED");
        assertThat(history(order)).containsExactly("PAID -> SHIPPED");
        assertThat(outboxCount(order)).isZero();

        send(event(order, "ShipmentDelivered"));

        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(history(order)).containsExactly("PAID -> SHIPPED", "SHIPPED -> DELIVERED");
        assertThat(outboxCount(order)).isZero();
        confirm(order);

        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(history(order)).containsExactly("PAID -> SHIPPED", "SHIPPED -> DELIVERED", "DELIVERED -> COMPLETED");
        assertThat(outboxCount(order)).isEqualTo(1);
        JsonNode envelope = completion(order);
        JsonNode payload = envelope.get("payload");
        assertThat(envelope.get("eventType").asString()).isEqualTo("OrderCompleted");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(order.id().toString());
        assertThat(payload.get("orderId").asString()).isEqualTo(order.id().toString());
        assertThat(payload.get("orderGroupId").asString()).isEqualTo(order.groupId().toString());
        assertThat(payload.get("buyerId").asString()).isEqualTo(order.buyerId().toString());
        assertThat(payload.get("sellerId").asString()).isEqualTo(order.sellerId().toString());
        assertThat(payload.get("orderType").asString()).isEqualTo("STANDARD");
        assertThat(payload.get("referenceId").isNull()).isTrue();
        assertThat(payload.get("orderNumber").asString()).isEqualTo(order.number());
        assertThat(payload.get("orderAmount").isString()).isTrue();
        assertThat(payload.get("orderAmount").asString()).isEqualTo("325000.00");
        assertThat(payload.get("shippingFee").isString()).isTrue();
        assertThat(payload.get("shippingFee").asString()).isEqualTo("25000.00");
        assertThat(payload.get("items")).hasSize(1);
        assertThat(payload.get("items").get(0).get("productId").asString()).isEqualTo(jdbc().queryForObject(
                "SELECT product_id::text FROM order_items WHERE order_id = ?", String.class, order.id()));
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(payload.get("orderVersion").asLong()).isEqualTo(3)
                .isEqualTo(jdbc().queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, order.id()));
        Instant storedCompletion = jdbc().queryForObject("""
                SELECT created_at FROM order_status_history WHERE order_id = ? AND new_status = 'COMPLETED'
                """, (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), order.id());
        assertThat(Instant.parse(payload.get("completedAt").asString())).isEqualTo(storedCompletion);
        assertThat(payload.get("receiptHtml").asString()).contains(order.number(), "Snapshot phone", "SKU-RECEIPT");
        assertThat(payload.get("receiptHtml").asString())
                .doesNotContain("Receipt Buyer", "0900000204", "204 Private Street", "Delivered to");
        assertThat(receipts.invoice(new CurrentUser(order.buyerId(), Set.of("BUYER"), Set.of("ORDER.VIEW")), order.id()))
                .contains("Receipt Buyer", "0900000204", "204 Private Street", order.number(), "300000.00");
        assertThat(envelope.get("traceId").asString()).hasSize(32);
        assertThat(jdbc().queryForList("SELECT DISTINCT changed_by FROM order_status_history WHERE order_id = ?",
                UUID.class, order.id())).containsExactlyInAnyOrder(SYSTEM, order.buyerId());
        assertThat(jdbc().queryForObject("SELECT topic FROM outbox WHERE aggregate_id = ?", String.class,
                order.id().toString())).isEqualTo(Topics.COMMERCE);
    }

    @Test
    void scheduledCompletionPublishesTheSameMoneyAndReceiptContract() {
        Fixture order = order(OrderStatus.SHIPPED, OrderType.STANDARD);
        jdbc().update("UPDATE orders SET shipping_fee = 12500.50, total_amount = 312500.50 WHERE id = ?", order.id());
        send(event(order, "ShipmentDelivered"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc().update("UPDATE orders SET delivered_at = ?, auto_complete_at = ? WHERE id = ?",
                now.minusDays(8), now.minusMinutes(1), order.id());

        assertThat(autoCompletion.runOnce()).isGreaterThanOrEqualTo(1);
        JsonNode payload = completion(order).get("payload");
        assertThat(payload.get("orderAmount").isString()).isTrue();
        assertThat(payload.get("orderAmount").asString()).isEqualTo("312500.50");
        assertThat(payload.get("shippingFee").isString()).isTrue();
        assertThat(payload.get("shippingFee").asString()).isEqualTo("12500.50");
        assertThat(payload.get("receiptHtml").asString()).contains(order.number());
        assertThat(payload.get("sellerId").asString()).isEqualTo(order.sellerId().toString());
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(outboxCount(order)).isEqualTo(1);
        autoCompletion.runOnce();
        assertThat(outboxCount(order)).isEqualTo(1);
    }

    @Test
    void completionPublishesTheDiscountedBuyerAmountWithoutAddingVoucherFundingBack() {
        Fixture order = order(OrderStatus.SHIPPED, OrderType.STANDARD);
        jdbc().update("UPDATE orders SET discount_amount=40000,shipping_fee=25000,total_amount=285000 WHERE id=?", order.id());
        jdbc().update("UPDATE order_groups SET total_amount=285000 WHERE id=?", order.groupId());
        send(event(order, "ShipmentDelivered"));
        confirm(order);

        JsonNode payload = completion(order).get("payload");
        assertThat(payload.get("orderAmount").asString()).isEqualTo("285000.00");
        assertThat(payload.get("shippingFee").asString()).isEqualTo("25000.00");
        assertThat(payload.get("receiptHtml").asString()).contains("40000.00", "285000.00");
        assertThat(outboxCount(order)).isEqualTo(1);
    }

    @Test
    void theSamePickupAndDeliveryAreAppliedOnlyOnce() {
        Fixture order = order(OrderStatus.PAID, OrderType.STANDARD);
        var pickup = event(order, "ShipmentPickedUp");
        var delivery = event(order, "ShipmentDelivered");
        send(pickup);
        send(pickup);
        send(delivery);
        send(delivery);

        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(outboxCount(order)).isZero();
        confirm(order);
        assertThatThrownBy(() -> confirm(order)).isInstanceOf(com.group5.common_core.exception.DomainException.class)
                .extracting("errorCode").isEqualTo("COMMERCE_ORDER_NOT_DELIVERED");

        assertThat(history(order)).hasSize(3);
        assertThat(outboxCount(order)).isEqualTo(1);
        assertThat(processed(pickup.eventId())).isEqualTo(1);
        assertThat(processed(delivery.eventId())).isEqualTo(1);
    }

    @Test
    void differentDeliveryEnvelopeIdsRacingForOneOrderWaitForOneBuyerCompletion() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, OrderType.STANDARD);
        var first = event(order, "ShipmentDelivered");
        var second = new EventEnvelope<>(UUID.randomUUID(), first.eventType(), first.aggregateId(),
                first.occurredAt(), first.traceId(), first.payload());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(event -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing start signal");
                send(event);
                return true;
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(history(order)).containsExactly("SHIPPED -> DELIVERED");
        assertThat(outboxCount(order)).isZero();
        confirm(order);
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(history(order)).containsExactly("SHIPPED -> DELIVERED", "DELIVERED -> COMPLETED");
        assertThat(outboxCount(order)).isEqualTo(1);
        assertThat(processed(first.eventId())).isEqualTo(1);
        assertThat(processed(second.eventId())).isEqualTo(1);
    }

    @Test
    void deliveryWithoutPickupWaitsForConfirmationAndLatePickupIsANoOp() {
        Fixture order = order(OrderStatus.PAID, OrderType.STANDARD);
        var delivery = event(order, "ShipmentDelivered");
        send(delivery);
        send(delivery);
        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(outboxCount(order)).isZero();
        confirm(order);
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(history(order)).hasSize(3);
        assertThat(processed(delivery.eventId())).isEqualTo(1);
        assertThat(pendingCount()).isZero();
        assertThat(retries.runOnce()).isZero();
        send(event(order, "ShipmentPickedUp"));
        assertThat(tracer.currentSpan()).isNull();
        ShipmentRetryJob restarted = new ShipmentRetryJob(pending, handler, transactions, tracer);
        assertThat(restarted.runOnce()).isZero();
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(outboxCount(order)).isEqualTo(1);
        assertThat(pendingCount()).isZero();
        assertThat(tracer.currentSpan()).isNull();
        assertThat(completion(order).get("traceId").asString()).hasSize(32);
        assertThat(restarted.runOnce()).isZero();
    }

    @Test
    void failureAfterWritingTheCompletionOutboxRollsBackConfirmationOnly() {
        Fixture order = order(OrderStatus.SHIPPED, OrderType.STANDARD);
        var delivery = event(order, "ShipmentDelivered");
        send(delivery);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Fail after completion outbox insert");
        }).when(publisher).orderCompleted(any(OrderCompleted.class));

        assertThatThrownBy(() -> confirm(order)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Fail after completion outbox insert");

        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(history(order)).containsExactly("SHIPPED -> DELIVERED");
        assertThat(outboxCount(order)).isZero();
        assertThat(processed(delivery.eventId())).isEqualTo(1);
        reset(publisher);
        confirm(order);
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(outboxCount(order)).isEqualTo(1);
    }

    @Test
    void pendingOrdersKeepPickupDurableUntilThePaymentArrives() {
        Fixture order = order(OrderStatus.PENDING, OrderType.STANDARD);
        var pickup = event(order, "ShipmentPickedUp");
        send(pickup);
        assertThat(status(order)).isEqualTo("PENDING");
        assertThat(history(order)).isEmpty();
        assertThat(processed(pickup.eventId())).isEqualTo(1);
        assertThat(pendingCount()).isEqualTo(1);
        assertThat(retries.runOnce()).isZero();
        jdbc().update("UPDATE orders SET status = 'PAID' WHERE id = ?", order.id());
        assertThat(retries.runOnce()).isEqualTo(1);
        assertThat(status(order)).isEqualTo("SHIPPED");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void aPendingDeliveryIsRetriedWithoutPublishingCompletionBeforeConfirmation() {
        Fixture order = order(OrderStatus.PENDING, OrderType.STANDARD);
        var delivery = event(order, "ShipmentDelivered");
        send(delivery);
        jdbc().update("UPDATE orders SET status='PAID' WHERE id=?", order.id());
        assertThat(retries.runOnce()).isEqualTo(1);
        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(history(order)).hasSize(2);
        assertThat(outboxCount(order)).isZero();
        assertThat(pendingCount()).isZero();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Failure after pending completion outbox insert");
        }).when(publisher).orderCompleted(any(OrderCompleted.class));

        assertThatThrownBy(() -> confirm(order)).isInstanceOf(IllegalStateException.class);
        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(history(order)).hasSize(2);
        assertThat(outboxCount(order)).isZero();
        assertThat(pendingCount()).isZero();
        reset(publisher);
        confirm(order);
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(outboxCount(order)).isEqualTo(1);
        assertThat(pendingCount()).isZero();
    }

    @Test
    void moreThanOneHundredWaitingEventsDoNotStarveAReadyCompletion() {
        Fixture waiting = order(OrderStatus.PENDING, OrderType.STANDARD);
        jdbc().update("""
                INSERT INTO pending_order_shipment_events (event_id, envelope)
                SELECT id, jsonb_set(?::jsonb, '{eventId}', to_jsonb(id::text))
                FROM (SELECT uuidv7() AS id FROM generate_series(1,101)) waiting
                """, JsonMapper.shared().writeValueAsString(event(waiting, "ShipmentDelivered")));
        Fixture ready = order(OrderStatus.PENDING, OrderType.STANDARD);
        send(event(ready, "ShipmentDelivered"));
        jdbc().update("UPDATE orders SET status='PAID' WHERE id=?", ready.id());

        assertThat(retries.runOnce()).isEqualTo(1);
        assertThat(status(ready)).isEqualTo("DELIVERED");
        assertThat(outboxCount(ready)).isZero();
        assertThat(status(waiting)).isEqualTo("PENDING");
        assertThat(pendingCount()).isEqualTo(101);
    }

    @Test
    void latePickupAndDeliveryCannotReverseCompletedOrCanceledOrders() {
        for (OrderStatus initial : List.of(OrderStatus.COMPLETED, OrderStatus.CANCELED, OrderStatus.REFUNDED)) {
            Fixture order = order(initial, OrderType.STANDARD);
            send(event(order, "ShipmentPickedUp"));
            send(event(order, "ShipmentDelivered"));
            assertThat(status(order)).isEqualTo(initial.name());
            assertThat(history(order)).isEmpty();
            assertThat(outboxCount(order)).isZero();
        }
    }

    @Test
    void auctionCompletionPreservesTheCanonicalAuctionReference() {
        Fixture order = order(OrderStatus.SHIPPED, OrderType.AUCTION);
        send(event(order, "ShipmentDelivered"));
        assertThat(outboxCount(order)).isZero();
        confirm(order);
        JsonNode payload = completion(order).get("payload");
        assertThat(payload.get("orderType").asString()).isEqualTo("AUCTION");
        assertThat(payload.get("referenceId").asString()).isEqualTo(order.referenceId().toString());
    }

    @Test
    void aShipmentForAnotherBuyerRollsBackWithoutChangingTheOrder() {
        Fixture order = order(OrderStatus.PAID, OrderType.STANDARD);
        var valid = event(order, "ShipmentPickedUp");
        ShipmentStatusChanged p = valid.payload();
        var otherBuyer = new ShipmentStatusChanged(p.shipmentId(), p.orderId(), UUID.randomUUID(), p.warehouseId(),
                p.carrier(), p.trackingCode(), p.status(), p.historyId(), p.shipmentVersion(), p.source(),
                p.carrierEventId(), p.actorId(), p.changedAt(), p.reason());
        var invalid = new EventEnvelope<>(valid.eventId(), valid.eventType(), valid.aggregateId(), valid.occurredAt(),
                valid.traceId(), otherBuyer);

        assertThatThrownBy(() -> send(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(status(order)).isEqualTo("PAID");
        assertThat(history(order)).isEmpty();
        assertThat(processed(invalid.eventId())).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ShipmentInTransit", "ShipmentFailed", "ShipmentReturned", "ShipmentStatusUpdated"})
    void carrierProgressWithoutPickupMovesPaidOrderToShippedExactlyOnce(String type) {
        Fixture order = order(OrderStatus.PAID, OrderType.STANDARD);
        var progress = event(order, type);
        send(progress);
        send(progress);
        assertThat(status(order)).isEqualTo("SHIPPED");
        assertThat(history(order)).containsExactly("PAID -> SHIPPED");
        send(event(order, "ShipmentPickedUp"));
        send(event(order, "ShipmentDelivered"));
        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(outboxCount(order)).isZero();
        confirm(order);
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(history(order)).hasSize(3);
        assertThat(outboxCount(order)).isEqualTo(1);
    }

    private void confirm(Fixture order) {
        var span = tracer.nextSpan().name("buyer-confirms-receipt").start();
        try (var ignored = tracer.withSpan(span)) {
            completions.confirm(new CurrentUser(order.buyerId(), Set.of("BUYER"), Set.of("ORDER.VIEW")), order.id());
        } finally { span.end(); }
    }

    private void send(EventEnvelope<ShipmentStatusChanged> event) {
        assertThat(tracer.currentSpan()).isNull();
        listener.receive(new ConsumerRecord<>(Topics.SHIPMENT, 0, 0, event.payload().orderId().toString(),
                new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(), event.occurredAt(),
                        event.traceId(), JsonMapper.shared().valueToTree(event.payload()))));
        assertThat(tracer.currentSpan()).isNull();
    }

    private static EventEnvelope<ShipmentStatusChanged> event(Fixture order, String type) {
        boolean delivered = "ShipmentDelivered".equals(type);
        String status = switch (type) {
            case "ShipmentInTransit" -> "IN_TRANSIT";
            case "ShipmentFailed" -> "DELIVERY_FAILED";
            case "ShipmentReturned" -> "RETURNED";
            case "ShipmentStatusUpdated" -> "OUT_FOR_DELIVERY";
            default -> delivered ? "DELIVERED" : "SHIPPED";
        };
        var payload = new ShipmentStatusChanged(order.shipmentId(), order.id(), order.buyerId(), UUID.randomUUID(),
                "GHN", "GHN-204", status, UUID.randomUUID(), delivered ? 2L : 1L,
                "CARRIER_WEBHOOK", "GHN:" + UUID.randomUUID(), SYSTEM, "2026-11-07T12:00:00Z", null);
        return new EventEnvelope<>(UUID.randomUUID(), type, payload.shipmentId().toString(), Instant.now(),
                "204shipment", payload);
    }

    private Fixture order(OrderStatus status, OrderType type) {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID group = jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 300000.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString());
        String number = "NX-SHIP-%08d".formatted(ORDER_NUMBERS.incrementAndGet());
        UUID reference = type == OrderType.AUCTION ? UUID.randomUUID() : null;
        UUID id = jdbc().queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, reference_id, status,
                                    subtotal, total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, CAST(? AS order_type), ?, CAST(? AS order_status), 300000.00, 300000.00, ?)
                RETURNING id
                """, UUID.class, group, buyer, seller, number, type.name(), reference, status.name(), UUID.randomUUID().toString());
        jdbc().update("""
                INSERT INTO order_items (order_id, product_id, sku_id, product_name, sku_code, quantity, unit_price, total_price)
                VALUES (?, ?, ?, 'Snapshot phone', 'SKU-RECEIPT', 2, 150000.00, 300000.00)
                """, id, UUID.randomUUID(), UUID.randomUUID());
        jdbc().update("""
                UPDATE orders SET shipping_info = ?::jsonb WHERE id = ?
                """, JsonMapper.shared().writeValueAsString(Map.of("recipient", Map.of("name", "Receipt Buyer",
                "phone", "0900000204", "address", Map.of("line1", "204 Private Street", "wardCode", "W204",
                        "provinceCode", "P204")))), id);
        return new Fixture(id, group, buyer, seller, number, UUID.randomUUID(), reference);
    }

    private String status(Fixture order) {
        return jdbc().queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, order.id());
    }

    private List<String> history(Fixture order) {
        return jdbc().queryForList("""
                SELECT previous_status::text || ' -> ' || new_status::text
                FROM order_status_history WHERE order_id = ? ORDER BY created_at, id
                """, String.class, order.id());
    }

    private long processed(UUID eventId) {
        return jdbc().queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Long.class, eventId, ShipmentStatusHandler.CONSUMER);
    }

    private long outboxCount(Fixture order) {
        return jdbc().queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderCompleted'",
                Long.class, order.id().toString());
    }

    private long pendingCount() {
        return jdbc().queryForObject("SELECT count(*) FROM pending_order_shipment_events", Long.class);
    }

    private JsonNode completion(Fixture order) {
        return JsonMapper.shared().readTree(jdbc().queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id = ?",
                String.class, order.id().toString()));
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }

    private record Fixture(UUID id, UUID groupId, UUID buyerId, UUID sellerId, String number, UUID shipmentId,
                           UUID referenceId) { }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({OrderStateMachine.class, ShipmentStatusHandler.class, ShipmentEventConsumer.class,
            OrderReceiptRenderer.class, OrderReceiptService.class, PendingShipmentEvents.class, ShipmentRetryJob.class})
    static class JpaConfig {
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.shared(); }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean Clock completionClock() { return Clock.fixed(Instant.now(), ZoneOffset.UTC); }

        @Bean OrderCompletionService completionService(OrderRepository orders, OrderStateMachine states,
                OrderEventPublisher events, OrderReceiptRenderer receipts, EntityManagerFactory factory, Clock clock) {
            return new OrderCompletionService(orders, states, events, receipts,
                    SharedEntityManagerCreator.createSharedEntityManager(factory), clock);
        }

        @Bean OrderCompletionJob completionJob(OrderRepository orders, OrderCompletionService completions,
                Tracer tracer, Clock clock) {
            return new OrderCompletionJob(orders, completions, tracer, clock);
        }

        @Bean
        Tracer tracer() {
            Tracer tracer = Mockito.mock(Tracer.class);
            ThreadLocal<Span> current = new ThreadLocal<>();
            when(tracer.currentSpan()).thenAnswer(invocation -> current.get());
            when(tracer.nextSpan()).thenAnswer(invocation -> {
                Span span = Mockito.mock(Span.class, Mockito.RETURNS_SELF);
                TraceContext context = Mockito.mock(TraceContext.class);
                when(context.traceId()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
                when(span.context()).thenReturn(context);
                return span;
            });
            when(tracer.withSpan(any(Span.class))).thenAnswer(invocation -> {
                Span previous = current.get();
                current.set(invocation.getArgument(0));
                return (Tracer.SpanInScope) () -> {
                    if (previous == null) current.remove(); else current.set(previous);
                };
            });
            return tracer;
        }

        @Bean
        DataSource dataSource() {
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_shipment",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_shipment")
                    .defaultSchema("commerce_shipment").load().migrate();
            return source;
        }

        @Bean
        IdempotentConsumer idempotentConsumer(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory),
                    new TransactionTemplate(transactions));
        }

        @Bean
        OrderEventPublisher orderEventPublisher(DataSource source, PlatformTransactionManager transactions, Tracer tracer) {
            return Mockito.spy(new OrderEventPublisher(new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactions),
                    new EventEnvelopeFactory(tracer, Clock.systemUTC())));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Order.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
