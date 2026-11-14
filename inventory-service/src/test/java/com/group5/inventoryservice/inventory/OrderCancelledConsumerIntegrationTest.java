package com.group5.inventoryservice.inventory;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-order-paid-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_CANCEL",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class OrderCancelledConsumerIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static { POSTGRES.start(); }

    private static final UUID SYSTEM_ACTOR = new UUID(0, 0);
    private static final JsonMapper JSON = JsonMapper.shared();
    @Autowired private OrderEventConsumer consumer;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private UUID warehouse;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_CANCEL', 'Order cancelled test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_CANCEL'", UUID.class);
    }

    @Test
    void orderCancelledAtTotal10Available8Reserved2Leaves10AvailableAndOneRelease() {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 8, 2, 0);
        UUID reservation = reservation(order, sku, 2, "HELD");

        consumer.receive(orderCancelled(UUID.randomUUID(), order, "STANDARD"));

        assertBalance(sku, 10, 10, 0, 0);
        assertThat(status(reservation)).isEqualTo("RELEASED");
        assertThat(movements(sku, "RELEASE")).containsExactly(Map.of("quantity", 2,
                "reference_id", reservation, "created_by", SYSTEM_ACTOR));
    }

    @Test
    void orderCancelledDeliveredTwiceLeavesTheBalanceIdenticalAndOneMovementRow() {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 8, 2, 0);
        reservation(order, sku, 2, "HELD");
        UUID eventId = UUID.randomUUID();
        consumer.receive(orderCancelled(eventId, order, "STANDARD"));
        Map<String, Object> afterFirst = balance(sku);

        consumer.receive(orderCancelled(eventId, order, "STANDARD"));

        assertThat(balance(sku)).isEqualTo(afterFirst);
        assertThat(movementCount()).isEqualTo(1);
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void aReservationTheExpiryJobAlreadyReleasedIsSkipped() {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 10, 0, 0);
        UUID reservation = reservation(order, sku, 2, "EXPIRED");
        UUID eventId = UUID.randomUUID();

        consumer.receive(orderCancelled(eventId, order, "STANDARD"));

        assertBalance(sku, 10, 10, 0, 0);
        assertThat(status(reservation)).isEqualTo("EXPIRED");
        assertThat(movementCount()).isZero();
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void aConfirmedReservationOfAPaidOrderGoesBackToStockWithOneReturnRow() {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 8, 0, 2);
        UUID reservation = reservation(order, sku, 2, "CONFIRMED");
        UUID eventId = UUID.randomUUID();

        consumer.receive(orderCancelled(eventId, order, "STANDARD"));

        assertBalance(sku, 10, 10, 0, 0);
        assertThat(status(reservation)).isEqualTo("RELEASED");
        assertThat(movements(sku, "RETURN")).containsExactly(Map.of("quantity", 2,
                "reference_id", reservation, "created_by", SYSTEM_ACTOR));
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void anAuctionOrderHoldWithItsOrderIdIsReleasedTheSameWay() {
        UUID order = UUID.randomUUID();
        UUID sku = stock(1, 0, 1, 0);
        UUID reservation = reservation(order, sku, 1, "HELD");

        consumer.receive(orderCancelled(UUID.randomUUID(), order, "AUCTION"));

        assertBalance(sku, 1, 1, 0, 0);
        assertThat(status(reservation)).isEqualTo("RELEASED");
        assertThat(movements(sku, "RELEASE")).hasSize(1);
    }

    @Test
    void aTwoLineOrderReleasesBothReservationsWithOneRowEach() {
        UUID order = UUID.randomUUID();
        UUID first = stock(10, 8, 2, 0);
        UUID second = stock(5, 4, 1, 0);
        reservation(order, first, 2, "HELD");
        reservation(order, second, 1, "HELD");

        consumer.receive(orderCancelled(UUID.randomUUID(), order, "STANDARD"));

        assertBalance(first, 10, 10, 0, 0);
        assertBalance(second, 5, 5, 0, 0);
        assertThat(movementCount()).isEqualTo(2);
    }

    private ConsumerRecord<String, EventEnvelope<JsonNode>> orderCancelled(UUID eventId, UUID order,
                                                                           String orderType) {
        String auction = "AUCTION".equals(orderType) ? "\"" + UUID.randomUUID() + "\"" : "null";
        JsonNode payload = JSON.readTree("""
                {"orderId":"%s","buyerId":"%s","orderNumber":"NX-TEST-0001","orderType":"%s",
                 "referenceId":%s,"cancelledAt":"2026-11-14T03:00:00Z","reason":"PAYMENT_DEADLINE_EXPIRED",
                 "orderGroupId":"%s","sellerId":"%s","orderVersion":2,"paidAmount":"0.00","currency":"VND",
                 "actorId":"%s"}
                """.formatted(order, UUID.randomUUID(), orderType, auction, UUID.randomUUID(), UUID.randomUUID(),
                SYSTEM_ACTOR));
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(eventId, "OrderCancelled", order.toString(),
                Instant.now(), "trace-order-cancelled", payload);
        return new ConsumerRecord<>(Topics.COMMERCE, 0, 0, order.toString(), envelope);
    }

    private UUID stock(int total, int available, int reserved, int unavailable) {
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity,
                                                reserved_quantity, unavailable_quantity)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sku, warehouse, total, available, reserved, unavailable);
        return sku;
    }

    private UUID reservation(UUID order, UUID sku, int quantity, String status) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_reservations (sku_id, warehouse_id, order_id, quantity, status, expires_at)
                VALUES (?, ?, ?, ?, CAST(? AS inventory_reservation_status), ?) RETURNING id
                """, UUID.class, sku, warehouse, order, quantity, status, OffsetDateTime.now().plusMinutes(15));
    }

    private void assertBalance(UUID sku, int total, int available, int reserved, int unavailable) {
        assertThat(balance(sku)).containsEntry("total_quantity", total).containsEntry("available_quantity", available)
                .containsEntry("reserved_quantity", reserved).containsEntry("unavailable_quantity", unavailable);
    }

    private Map<String, Object> balance(UUID sku) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?",
                sku, warehouse);
    }

    private String status(UUID reservation) {
        return jdbc.queryForObject("SELECT status::text FROM inventory_reservations WHERE id = ?", String.class,
                reservation);
    }

    private List<Map<String, Object>> movements(UUID sku, String type) {
        return jdbc.queryForList("""
                SELECT quantity, reference_id, created_by FROM inventory_movements
                WHERE sku_id = ? AND movement_type = CAST(? AS inventory_movement_type)
                """, sku, type);
    }

    private int processed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, eventId, OrderEventConsumer.ORDER_CANCELLED_CONSUMER);
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class);
    }
}
