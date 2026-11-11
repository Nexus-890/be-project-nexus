package com.group5.inventoryservice.inventory;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-order-paid-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_PAID",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
@ExtendWith(OutputCaptureExtension.class)
class OrderPaidConsumerIntegrationTest {
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
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_PAID', 'Order paid test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_PAID'", UUID.class);
    }

    @Test
    void orderPaidAtTotal10Available8Reserved2Leaves8Available0Reserved2UnavailableAndOneCommit() {
        UUID order = UUID.randomUUID();
        UUID sku = heldStock(sku("10"), 10, 2);
        UUID reservation = hold(order, sku, 2);

        consumer.receive(orderPaid(UUID.randomUUID(), order, "STANDARD"));

        assertBalance(sku, 10, 8, 0, 2);
        assertThat(status(reservation)).isEqualTo("CONFIRMED");
        assertThat(movements(sku, "COMMIT")).containsExactly(Map.of("quantity", 2,
                "reference_id", reservation, "created_by", SYSTEM_ACTOR));
    }

    @Test
    void anAuctionOrderHoldWithItsOrderIdIsConfirmedTheSameWay() {
        UUID order = UUID.randomUUID();
        UUID sku = heldStock(sku("10"), 1, 1);
        UUID reservation = hold(order, sku, 1);

        consumer.receive(orderPaid(UUID.randomUUID(), order, "AUCTION"));

        assertBalance(sku, 1, 0, 0, 1);
        assertThat(status(reservation)).isEqualTo("CONFIRMED");
        assertThat(movements(sku, "COMMIT")).containsExactly(Map.of("quantity", 1,
                "reference_id", reservation, "created_by", SYSTEM_ACTOR));
    }

    @Test
    void orderPaidDeliveredTwiceLeavesTheBalanceOfOneDeliveryAndOneCommitRow() {
        UUID order = UUID.randomUUID();
        UUID sku = heldStock(sku("10"), 10, 2);
        hold(order, sku, 2);
        UUID eventId = UUID.randomUUID();
        consumer.receive(orderPaid(eventId, order, "STANDARD"));
        Map<String, Object> afterFirst = balance(sku);

        consumer.receive(orderPaid(eventId, order, "STANDARD"));

        assertThat(balance(sku)).isEqualTo(afterFirst);
        assertThat(movements(sku, "COMMIT")).hasSize(1);
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void anOrderWithNoReservationCompletesAndLogsOneInfoLine(CapturedOutput output) {
        UUID order = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        consumer.receive(orderPaid(eventId, order, "STANDARD"));

        assertThat(movementCount()).isZero();
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(output.getAll().lines().filter(line -> line.contains("ORDER_PAID_NO_RESERVATION")))
                .singleElement().asString().contains(" INFO ").contains("orderId=" + order);
    }

    @Test
    void aTwoLineOrderConfirmsBothReservationsWithOneCommitRowEach() {
        UUID order = UUID.randomUUID();
        UUID first = heldStock(sku("10"), 10, 2);
        UUID second = heldStock(sku("20"), 5, 1);
        UUID firstHold = hold(order, first, 2);
        UUID secondHold = hold(order, second, 1);

        consumer.receive(orderPaid(UUID.randomUUID(), order, "STANDARD"));

        assertBalance(first, 10, 8, 0, 2);
        assertBalance(second, 5, 4, 0, 1);
        assertThat(status(firstHold)).isEqualTo("CONFIRMED");
        assertThat(status(secondHold)).isEqualTo("CONFIRMED");
        assertThat(movementCount()).isEqualTo(2);
        assertThat(movements(first, "COMMIT")).containsExactly(Map.of("quantity", 2,
                "reference_id", firstHold, "created_by", SYSTEM_ACTOR));
        assertThat(movements(second, "COMMIT")).containsExactly(Map.of("quantity", 1,
                "reference_id", secondHold, "created_by", SYSTEM_ACTOR));
    }

    @Test
    void aTwoLineOrderWhoseSecondLineCannotCommitConfirmsNeither() {
        UUID order = UUID.randomUUID();
        UUID first = heldStock(sku("10"), 10, 2);
        UUID second = heldStock(sku("20"), 5, 0);
        UUID firstHold = hold(order, first, 2);
        UUID secondHold = hold(order, second, 1);
        Map<String, Object> firstBefore = balance(first);
        Map<String, Object> secondBefore = balance(second);

        assertThatThrownBy(() -> consumer.receive(orderPaid(UUID.randomUUID(), order, "STANDARD")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(balance(first)).isEqualTo(firstBefore);
        assertThat(balance(second)).isEqualTo(secondBefore);
        assertThat(status(firstHold)).isEqualTo("HELD");
        assertThat(status(secondHold)).isEqualTo("HELD");
        assertThat(movementCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isZero();
    }

    @Test
    void anOrderPaidWithoutOrderIdConfirmsNoReservationAndIsNotMarkedProcessed() {
        UUID first = heldStock(sku("10"), 10, 2);
        UUID second = heldStock(sku("20"), 5, 1);
        UUID unattachedFirst = hold(null, first, 2);
        UUID unattachedSecond = hold(null, second, 1);
        Map<String, Object> firstBefore = balance(first);
        Map<String, Object> secondBefore = balance(second);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.receive(event(eventId, UUID.randomUUID().toString(), null, "STANDARD")))
                .isInstanceOf(InvalidOrderEventException.class);

        assertThat(status(unattachedFirst)).isEqualTo("HELD");
        assertThat(status(unattachedSecond)).isEqualTo("HELD");
        assertThat(balance(first)).isEqualTo(firstBefore);
        assertThat(balance(second)).isEqualTo(secondBefore);
        assertThat(movementCount()).isZero();
        assertThat(processed(eventId)).isZero();
    }

    @Test
    void anOrderPaidWhoseAggregateIdIsAnotherOrderConfirmsNothingAndIsNotMarkedProcessed() {
        UUID order = UUID.randomUUID();
        UUID sku = heldStock(sku("10"), 10, 2);
        UUID reservation = hold(order, sku, 2);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.receive(event(eventId, UUID.randomUUID().toString(), order, "STANDARD")))
                .isInstanceOf(InvalidOrderEventException.class);

        assertThat(status(reservation)).isEqualTo("HELD");
        assertBalance(sku, 10, 8, 2, 0);
        assertThat(movementCount()).isZero();
        assertThat(processed(eventId)).isZero();
    }

    private ConsumerRecord<String, EventEnvelope<JsonNode>> orderPaid(UUID eventId, UUID order, String orderType) {
        return event(eventId, order.toString(), order, orderType);
    }

    private ConsumerRecord<String, EventEnvelope<JsonNode>> event(UUID eventId, String aggregateId, UUID order,
                                                                  String orderType) {
        String auction = "AUCTION".equals(orderType) ? "\"" + UUID.randomUUID() + "\"" : "null";
        String orderId = order == null ? "" : "\"orderId\":\"" + order + "\",";
        JsonNode payload = JSON.readTree("""
                {%s"buyerId":"%s","orderNumber":"NX-TEST-0001","orderType":"%s",
                 "referenceId":%s,"paidAt":"2026-11-11T03:00:00Z","orderGroupId":"%s","sellerId":"%s",
                 "orderVersion":1,"paidAmount":"300000.00","currency":"VND","warehouseId":"%s",
                 "shippingFee":"0.00","shippingInfo":{"items":[]}}
                """.formatted(orderId, UUID.randomUUID(), orderType, auction, UUID.randomUUID(), UUID.randomUUID(),
                        warehouse));
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(eventId, "OrderPaid", aggregateId,
                Instant.now(), "trace-order-paid", payload);
        return new ConsumerRecord<>(Topics.COMMERCE, 0, 0, aggregateId, envelope);
    }

    private static UUID sku(String prefix) {
        return UUID.fromString(prefix + UUID.randomUUID().toString().substring(2));
    }

    private UUID heldStock(UUID sku, int total, int reserved) {
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity,
                                                reserved_quantity)
                VALUES (?, ?, ?, ?, ?)
                """, sku, warehouse, total, total - reserved, reserved);
        return sku;
    }

    private UUID hold(UUID order, UUID sku, int quantity) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_reservations (sku_id, warehouse_id, order_id, quantity, status, expires_at)
                VALUES (?, ?, ?, ?, 'HELD', ?) RETURNING id
                """, UUID.class, sku, warehouse, order, quantity, OffsetDateTime.now().plusMinutes(15));
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
                Integer.class, eventId, OrderEventConsumer.ORDER_PAID_CONSUMER);
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class);
    }
}
