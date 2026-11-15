package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed;
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
        "nexus.outbox.relay.enabled=false", "nexus.cart.expiry-cron=-", "nexus.order.shipment-retry-interval=1h",
        "management.tracing.export.zipkin.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record", "spring.kafka.listener.missing-topics-fatal=true",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json"
})
@EmbeddedKafka(partitions = 1, topics = {Topics.PAYMENT, Topics.SHIPMENT, Topics.INVENTORY},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutPaymentConfirmedKafkaTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
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
