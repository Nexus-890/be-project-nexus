package com.group5.inventoryservice.reservation;

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
        "nexus.inventory.default-warehouse-code=WH_EXPIRY",
        "nexus.inventory.reservation-expiry-interval=1h",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class ReservationExpiryJobIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static { POSTGRES.start(); }

    private static final UUID SYSTEM_ACTOR = new UUID(0, 0);
    @Autowired private ReservationExpiryJob job;
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
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_EXPIRY', 'Expiry test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_EXPIRY'", UUID.class);
    }

    @Test
    void aHeldReservationOfTwoPastItsDeadlineIsExpiredAndTheTwoUnitsAreAvailableAgain() {
        UUID order = UUID.randomUUID();
        UUID sku = stock(10, 8, 2, 0);
        UUID reservation = reservation(order, null, sku, 2, "HELD", "-1 minute");

        assertThat(job.runOnce()).isEqualTo(1);

        assertBalance(sku, 10, 10, 0, 0);
        assertThat(status(reservation)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT released_at IS NOT NULL FROM inventory_reservations WHERE id = ?",
                Boolean.class, reservation)).isTrue();
        assertThat(movements(sku)).containsExactly(Map.of("movement_type", "RELEASE", "quantity", 2,
                "reference_id", reservation, "created_by", SYSTEM_ACTOR));
        List<JsonNode> events = events(reservation);
        assertThat(events).hasSize(1);
        JsonNode payload = events.getFirst().get("payload");
        assertThat(payload.get("orderId").asString()).isEqualTo(order.toString());
        assertThat(payload.get("skuId").asString()).isEqualTo(sku.toString());
        assertThat(payload.get("warehouseId").asString()).isEqualTo(warehouse.toString());
        assertThat(payload.get("quantity").asInt()).isEqualTo(2);
        assertThat(payload.get("expiresAt").asString()).endsWith("Z");
        assertThat(payload.get("releasedAt").asString()).endsWith("Z");
    }

    @Test
    void aConfirmedReservationPastItsDeadlineIsUntouched() {
        UUID sku = stock(10, 8, 0, 2);
        UUID reservation = reservation(UUID.randomUUID(), null, sku, 2, "CONFIRMED", "-1 minute");

        assertThat(job.runOnce()).isZero();

        assertBalance(sku, 10, 8, 0, 2);
        assertThat(status(reservation)).isEqualTo("CONFIRMED");
        assertThat(movements(sku)).isEmpty();
    }

    @Test
    void aHeldAuctionReservationPastItsDeadlineIsUntouched() {
        UUID sku = stock(1, 0, 1, 0);
        UUID reservation = reservation(null, UUID.randomUUID(), sku, 1, "HELD", "-1 minute");

        assertThat(job.runOnce()).isZero();

        assertBalance(sku, 1, 0, 1, 0);
        assertThat(status(reservation)).isEqualTo("HELD");
        assertThat(events(reservation)).isEmpty();
    }

    @Test
    void aHeldReservationBeforeItsDeadlineIsUntouched() {
        UUID sku = stock(10, 8, 2, 0);
        UUID reservation = reservation(UUID.randomUUID(), null, sku, 2, "HELD", "10 minutes");

        assertThat(job.runOnce()).isZero();

        assertThat(status(reservation)).isEqualTo("HELD");
        assertBalance(sku, 10, 8, 2, 0);
    }

    @Test
    void aSecondRunChangesNothingAndDoesNotRaiseAvailableAgain() {
        UUID sku = stock(10, 6, 4, 0);
        reservation(UUID.randomUUID(), null, sku, 2, "HELD", "-1 minute");
        reservation(UUID.randomUUID(), null, sku, 2, "HELD", "-2 minutes");

        assertThat(job.runOnce()).isEqualTo(2);
        assertThat(job.runOnce()).isZero();

        assertBalance(sku, 10, 10, 0, 0);
        assertThat(movements(sku)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(2);
    }

    @Test
    void theJobsQueryUsesTheExpiringIndexWithTenThousandReservationsAndThreeDue() {
        UUID sku = stock(20000, 0, 20000, 0);
        jdbc.update("""
                INSERT INTO inventory_reservations (sku_id, warehouse_id, order_id, quantity, status, expires_at)
                SELECT ?, ?, gen_random_uuid(), 1, CAST(CASE WHEN g % 2 = 0 THEN 'CONFIRMED' ELSE 'HELD' END
                       AS inventory_reservation_status), now() + interval '15 minutes'
                FROM generate_series(1, 9997) g
                """, sku, warehouse);
        for (int i = 0; i < 3; i++) {
            reservation(UUID.randomUUID(), null, sku, 1, "HELD", "-1 minute");
        }
        jdbc.execute("ANALYZE inventory_reservations");

        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT id FROM inventory_reservations
                WHERE status = 'HELD' AND auction_id IS NULL AND expires_at < now()
                ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED
                """, String.class));

        assertThat(plan).contains("idx_reservations_expiring");
        assertThat(job.runOnce()).isEqualTo(3);
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

    private UUID reservation(UUID order, UUID auction, UUID sku, int quantity, String status, String expiresIn) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_reservations (sku_id, warehouse_id, order_id, auction_id, quantity, status,
                                                    expires_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS inventory_reservation_status), now() + CAST(? AS interval))
                RETURNING id
                """, UUID.class, sku, warehouse, order, auction, quantity, status, expiresIn);
    }

    private void assertBalance(UUID sku, int total, int available, int reserved, int unavailable) {
        assertThat(jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?",
                sku, warehouse))
                .containsEntry("total_quantity", total).containsEntry("available_quantity", available)
                .containsEntry("reserved_quantity", reserved).containsEntry("unavailable_quantity", unavailable);
    }

    private String status(UUID reservation) {
        return jdbc.queryForObject("SELECT status::text FROM inventory_reservations WHERE id = ?", String.class,
                reservation);
    }

    private List<Map<String, Object>> movements(UUID sku) {
        return jdbc.queryForList("""
                SELECT movement_type::text AS movement_type, quantity, reference_id, created_by
                FROM inventory_movements WHERE sku_id = ?
                """, sku);
    }

    private List<JsonNode> events(UUID reservation) {
        return jdbc.queryForList("""
                        SELECT payload::text FROM outbox
                        WHERE event_type = 'InventoryReservationExpired' AND aggregate_id = ?
                          AND topic = 'nexus.inventory.events'
                        """, String.class, reservation.toString()).stream()
                .map(JsonMapper.shared()::readTree)
                .toList();
    }
}
