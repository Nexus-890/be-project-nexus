package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed;
import com.group5.commerceservice.order.event.OrderRefundCompleted;
import com.group5.commerceservice.order.service.RefundStatusHandler;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Kiểm chứng serialization và listener Kafka thật chuyển đủ hai đơn sang PAID trong giới hạn thời gian. */
@SpringBootTest(properties = {
        "spring.config.name=commerce-confirmation-kafka-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "nexus.outbox.relay.enabled=true", "nexus.cart.expiry-cron=-", "nexus.order.shipment-retry-interval=1h", "nexus.order.expiry-interval=1h",
        "management.tracing.export.zipkin.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record", "spring.kafka.listener.missing-topics-fatal=true",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json"
})
@EmbeddedKafka(partitions = 1, topics = {Topics.PAYMENT, Topics.SHIPMENT, Topics.INVENTORY, Topics.COMMERCE, Topics.AUCTION},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutPaymentConfirmedKafkaTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.commerceservice.PostgresTestDatabase("postgres:18-alpine");
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderExpiryJob expiry;
    @Autowired @Qualifier(EventKafka.TEMPLATE) private KafkaTemplate<String, EventEnvelope<?>> kafka;

    /** Gửi confirmation qua broker rồi replay, đối chiếu trạng thái, lịch sử và outbox trong PostgreSQL. */
    @Test
    void serializedGroupConfirmationPaysBothOrdersWithinTenSecondsAndKafkaReplayHasNoExtraEffects() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID group = jdbc.queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 350000.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString());
        UUID first = order(group, buyer, warehouse, "200000.00");
        UUID second = order(group, buyer, warehouse, "150000.00");
        Instant now = Instant.now();
        var payment = new CheckoutPaymentConfirmed(UUID.randomUUID(), group, UUID.randomUUID(), buyer, "VND", "350000.00",
                now.toString(), 1L, List.of(new CheckoutPaymentConfirmed.Instruction(UUID.randomUUID(), first, "200000.00"),
                new CheckoutPaymentConfirmed.Instruction(UUID.randomUUID(), second, "150000.00")));
        var event = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentConfirmed", payment.sessionId().toString(),
                now, "5be44b49b86e4401a9f76c4016f56e9f", payment);

        // Kafka key là groupId, còn aggregate trong envelope giữ sessionId đúng contract.
        kafka.send(Topics.PAYMENT, group.toString(), event).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status FROM order_groups WHERE id = ?", String.class, group)).isEqualTo("PAID");
            assertThat(jdbc.queryForList("SELECT status::text FROM orders WHERE order_group_id = ?", String.class, group))
                    .containsExactly("PAID", "PAID");
            assertThat(history(first, second)).isEqualTo(2);
            assertThat(events(first, second)).isEqualTo(2);
        });
        // Replay có eventId mới nằm sau bản trùng, chứng minh consumer đã đi qua cả hai record trong partition.
        kafka.send(Topics.PAYMENT, group.toString(), event).get(10, TimeUnit.SECONDS);
        var sameReceipt = new EventEnvelope<>(UUID.randomUUID(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), event.payload());
        kafka.send(Topics.PAYMENT, group.toString(), sameReceipt).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?", Integer.class,
                sameReceipt.eventId(), CheckoutPaymentConfirmedConsumer.CONSUMER)).isEqualTo(1));
        assertThat(history(first, second)).isEqualTo(2);
        assertThat(events(first, second)).isEqualTo(2);
    }

    @Test
    void brokerConfirmationAndRefundPublishVoucherTransitionsExactlyOnce() throws Exception {
        var reserved = reserveVoucher();
        UUID buyer = reserved.buyer();
        UUID group = reserved.group();
        UUID order = reserved.order();
        UUID claim = reserved.claim();
        UUID usage = reserved.usage();

        Instant now = Instant.now();
        UUID instruction = UUID.randomUUID();
        var payment = new CheckoutPaymentConfirmed(UUID.randomUUID(), group, UUID.randomUUID(), buyer, "VND",
                "8900.00", now.toString(), 1L,
                List.of(new CheckoutPaymentConfirmed.Instruction(instruction, order, "8900.00")));
        var paid = new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentConfirmed", payment.sessionId().toString(),
                now, "5be44b49b86e4401a9f76c4016f56e9f", payment);
        kafka.send(Topics.PAYMENT, group.toString(), paid).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(usageStatus(usage)).isEqualTo("REDEEMED");
            assertThat(jdbc.queryForObject("SELECT status::text FROM promo_customer_vouchers WHERE id = ?", String.class, claim))
                    .isEqualTo("USED");
            assertThat(published(usage, "VoucherRedeemed")).isEqualTo(1);
        });

        jdbc.update("UPDATE orders SET status = 'CANCELED' WHERE id = ?", order);
        var refund = new OrderRefundCompleted(UUID.randomUUID(), order, group, payment.sessionId(), instruction,
                payment.transactionId(), "8900.00", "VND", "COMPLETED", "Kafka test refund", new UUID(0, 0),
                1L, 2L, now.toString(), UUID.randomUUID(), Instant.now().toString(), buyer);
        var event = new EventEnvelope<>(UUID.randomUUID(), "OrderRefundCompleted", order.toString(), Instant.now(),
                "5be44b49b86e4401a9f76c4016f56e9f", refund);
        kafka.send(Topics.PAYMENT, order.toString(), event).get(10, TimeUnit.SECONDS);
        kafka.send(Topics.PAYMENT, order.toString(), event).get(10, TimeUnit.SECONDS);
        var replay = new EventEnvelope<>(UUID.randomUUID(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), refund);
        kafka.send(Topics.PAYMENT, order.toString(), replay).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(usageStatus(usage)).isEqualTo("REVERSED");
            assertThat(jdbc.queryForObject("SELECT reversed_platform_amount FROM promo_voucher_usage_allocations WHERE voucher_usage_id = ?",
                    java.math.BigDecimal.class, usage)).isEqualByComparingTo("10");
            assertThat(published(usage, "VoucherReversed")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                    Integer.class, replay.eventId(), RefundStatusHandler.CONSUMER)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'VoucherReversed'",
                    Integer.class, usage.toString())).isEqualTo(1);
        });
    }

    @Test
    void expiryJobReleasesVoucherAndPublishesTheEventThroughTheRelay() {
        var reserved = reserveVoucher();
        jdbc.update("UPDATE order_groups SET due_at = now() - interval '1 second' WHERE id = ?", reserved.group());
        jdbc.update("UPDATE orders SET payment_due_at = now() - interval '1 second' WHERE id = ?", reserved.order());
        jdbc.update("UPDATE promo_voucher_usages SET reserved_until = now() - interval '1 second' WHERE id = ?", reserved.usage());
        assertThat(expiry.runOnce()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(usageStatus(reserved.usage())).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT committed_uses FROM promo_vouchers WHERE id = ?", Integer.class,
                    reserved.voucher())).isZero();
            assertThat(jdbc.queryForObject("SELECT status::text FROM promo_customer_vouchers WHERE id = ?", String.class,
                    reserved.claim())).isEqualTo("CLAIMED");
            assertThat(published(reserved.usage(), "VoucherReleased")).isEqualTo(1);
        });
    }

    private Reservation reserveVoucher() {
        UUID buyer = UUID.randomUUID();
        UUID group = jdbc.queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 8900, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString());
        UUID order = order(group, buyer, UUID.randomUUID(), "8900.00");
        jdbc.update("UPDATE orders SET subtotal = 8910, discount_amount = 10 WHERE id = ?", order);
        jdbc.update("UPDATE order_items SET unit_price = 8910, total_price = 8910 WHERE order_id = ?", order);

        UUID voucher = jdbc.queryForObject("""
                INSERT INTO promo_vouchers (code, name, source_type, discount_type, discount_value, funding_type,
                    product_scope, claim_start_at, claim_end_at, valid_from, valid_until, status,
                    claim_count, committed_uses, committed_discount)
                VALUES (?, 'Kafka voucher', 'PLATFORM', 'FIXED', 10, 'PLATFORM', 'ALL',
                    now() - interval '1 day', now() + interval '1 day', now() - interval '1 day', now() + interval '1 day',
                    'ACTIVE', 1, 1, 10) RETURNING id
                """, UUID.class, UUID.randomUUID().toString());
        UUID claim = jdbc.queryForObject("""
                INSERT INTO promo_customer_vouchers (customer_id, voucher_id, status, expires_at)
                VALUES (?, ?, 'CLAIMED', now() + interval '1 day') RETURNING id
                """, UUID.class, buyer, voucher);
        UUID usage = jdbc.queryForObject("""
                INSERT INTO promo_voucher_usages (customer_voucher_id, order_id, idempotency_key, status,
                    eligible_amount, discount_amount, seller_funded_amount, platform_funded_amount, voucher_snapshot, reserved_until)
                VALUES (?, ?, ?, 'RESERVED', 8910, 10, 0, 10, '{"code":"KAFKA"}', now() + interval '15 minutes') RETURNING id
                """, UUID.class, claim, order, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO promo_voucher_usage_allocations (voucher_usage_id, order_item_id, seller_id,
                    eligible_amount, discount_amount, seller_funded_amount, platform_funded_amount)
                SELECT ?, i.id, o.seller_id, 8910, 10, 0, 10
                FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.id = ?
                """, usage, order);
        return new Reservation(buyer, group, order, voucher, claim, usage);
    }

    private record Reservation(UUID buyer, UUID group, UUID order, UUID voucher, UUID claim, UUID usage) { }

    private String usageStatus(UUID usage) {
        return jdbc.queryForObject("SELECT status::text FROM promo_voucher_usages WHERE id = ?", String.class, usage);
    }

    private int published(UUID usage, String type) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = ?
                    AND topic = 'nexus.commerce.events' AND published_at IS NOT NULL
                """, Integer.class, usage.toString(), type);
    }

    /** Seed snapshot và order_items đầy đủ cho từng đơn, không gọi Catalog, Inventory hay Shipment. */
    private UUID order(UUID group, UUID buyer, UUID warehouse, String amount) {
        String shipping = JsonMapper.shared().writeValueAsString(Map.of("warehouseId", warehouse,
                "recipient", Map.of("name", "Kafka snapshot recipient", "phone", "0900000156",
                        "address", Map.of("line1", "Frozen street", "wardCode", "W156", "districtCode", "D156", "provinceCode", "P156"))));
        UUID order = jdbc.queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, subtotal,
                                    total_amount, shipping_info, idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS numeric), CAST(? AS numeric), CAST(? AS jsonb), ?) RETURNING id
                """, UUID.class, group, buyer, UUID.randomUUID(), "NX-KAFKA-" + UUID.randomUUID().toString().substring(0, 20),
                amount, amount, shipping, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO order_items (order_id, product_id, sku_id, product_name, quantity, unit_price, total_price)
                VALUES (?, ?, ?, 'Stored Kafka item', 1, CAST(? AS numeric), CAST(? AS numeric))
                """, order, UUID.randomUUID(), UUID.randomUUID(), amount, amount);
        return order;
    }

    private int history(UUID first, UUID second) {
        return jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id IN (?, ?)", Integer.class, first, second);
    }

    private int events(UUID first, UUID second) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id IN (?, ?) AND event_type = 'OrderPaid'",
                Integer.class, first.toString(), second.toString());
    }
}
