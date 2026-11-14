package com.group5.commerceservice.order;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import io.micrometer.tracing.Tracer;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Gửi qua broker thật khi observation tắt; test không tự mở span cho listener. */
@SpringBootTest(properties = {
        "spring.config.name=shipment-listener-kafka-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "nexus.outbox.relay.enabled=false", "nexus.cart.expiry-cron=-", "nexus.order.shipment-retry-interval=1h",
        "management.tracing.export.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record", "spring.kafka.listener.missing-topics-fatal=true",
        "spring.kafka.listener.observation-enabled=false", "spring.kafka.template.observation-enabled=false",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json"
})
@EmbeddedKafka(partitions = 1, topics = {Topics.PAYMENT, Topics.SHIPMENT, Topics.INVENTORY},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShipmentEventConsumerKafkaTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Tracer tracer;
    @Autowired @Qualifier(EventKafka.TEMPLATE) private KafkaTemplate<String, EventEnvelope<?>> kafka;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void untracedKafkaDeliveryCompletesTheOrderAndReplayDoesNotDuplicateEffects(boolean missingPickup) throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID shipment = UUID.randomUUID();
        UUID group = jdbc.queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 300000.00, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString());
        UUID order = jdbc.queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status,
                                    subtotal, total_amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', 'PAID', 300000.00, 300000.00, ?) RETURNING id
                """, UUID.class, group, buyer, UUID.randomUUID(), "NX-KAFKA-" + UUID.randomUUID().toString().substring(0, 20),
                UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO order_items (order_id, product_id, sku_id, product_name, quantity, unit_price, total_price)
                VALUES (?, ?, ?, 'Kafka receipt item', 2, 150000.00, 300000.00)
                """, order, UUID.randomUUID(), UUID.randomUUID());

        var pickup = event(order, buyer, shipment, false);
        var delivery = event(order, buyer, shipment, true);
        assertThat(tracer.currentSpan()).isNull();
        if (!missingPickup) {
            publish(order, pickup);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(status(order)).isEqualTo("SHIPPED"));
        }
        publish(order, delivery);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status(order)).isEqualTo("COMPLETED");
            assertThat(processed(delivery.eventId())).isEqualTo(1);
        });

        // Event mới cùng partition làm mốc xác nhận listener đã xử lý qua các record replay.
        publish(order, pickup);
        publish(order, delivery);
        var barrier = event(order, buyer, shipment, true);
        publish(order, barrier);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(processed(barrier.eventId())).isEqualTo(1));

        assertThat(jdbc.queryForList("""
                SELECT previous_status::text || ' -> ' || new_status::text
                FROM order_status_history WHERE order_id = ? ORDER BY created_at, id
                """, String.class, order))
                .containsExactly("PAID -> SHIPPED", "SHIPPED -> DELIVERED", "DELIVERED -> COMPLETED");
        var rows = jdbc.queryForList("""
                SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderCompleted'
                """, String.class, order.toString());
        assertThat(rows).hasSize(1);
        JsonNode completion = JsonMapper.shared().readTree(rows.getFirst());
        assertThat(completion.get("traceId").asString()).matches("(?:[0-9a-f]{16}|[0-9a-f]{32})");
        assertThat(completion.get("payload").get("receiptHtml").asString()).contains("Kafka receipt item", "300000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_order_shipment_events", Integer.class)).isZero();
        assertThat(tracer.currentSpan()).isNull();
    }

    private void publish(UUID order, EventEnvelope<ShipmentStatusChanged> event) throws Exception {
        kafka.send(Topics.SHIPMENT, order.toString(), event).get(10, TimeUnit.SECONDS);
    }

    private String status(UUID order) {
        return jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, order);
    }

    private int processed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, eventId, ShipmentStatusHandler.CONSUMER);
    }

    private static EventEnvelope<ShipmentStatusChanged> event(UUID order, UUID buyer, UUID shipment, boolean delivered) {
        var payload = new ShipmentStatusChanged(shipment, order, buyer, UUID.randomUUID(), "GHN", "GHN-204",
                delivered ? "DELIVERED" : "SHIPPED", UUID.randomUUID(), delivered ? 2L : 1L,
                "POLLER", "GHN:" + UUID.randomUUID(), new UUID(0, 0), Instant.now().toString(), null);
        return new EventEnvelope<>(UUID.randomUUID(), delivered ? "ShipmentDelivered" : "ShipmentPickedUp",
                shipment.toString(), Instant.now(), "5be44b49b86e4401a9f76c4016f56e9f", payload);
    }
}
