package com.group5.inventoryservice.inventory;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.reservation.ReservationExpiryJob;
import com.group5.inventoryservice.reservation.dto.request.ReserveRequest;
import com.group5.inventoryservice.reservation.service.ReservationService;
import jakarta.servlet.Filter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/auction-stock-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/inventory-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.admin.auto-create=false", "spring.kafka.listener.auto-startup=false",
        "nexus.outbox.relay.enabled=false"
})
class AuctionStockIntegrationTest {
    private static final String PATH = "/internal/v1/inventory/reservations";
    private static final String TRACE_ID = "8f30a3a71d670bc4";
    private static final String DATABASE = "inventory_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ADMIN_URL = System.getenv("TEST_POSTGRES_ADMIN_URL");
    private static final String USER = System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "");
    private static PostgreSQLContainer postgres;

    @Autowired JdbcTemplate jdbc;
    @Autowired AuctionStockConsumer auctionEvents;
    @Autowired OrderEventConsumer orderEvents;
    @Autowired ReservationExpiryJob expiry;
    @Autowired ReservationService reservations;
    @Autowired WebApplicationContext context;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        if (ADMIN_URL == null || ADMIN_URL.isBlank()) {
            postgres = new PostgreSQLContainer("postgres:18");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            admin("CREATE DATABASE " + DATABASE);
            registry.add("spring.datasource.url",
                    () -> ADMIN_URL.substring(0, ADMIN_URL.lastIndexOf('/') + 1) + DATABASE);
            registry.add("spring.datasource.username", () -> USER);
            registry.add("spring.datasource.password", () -> PASSWORD);
        }
    }

    @AfterAll
    static void stopDatabase() throws SQLException {
        if (postgres != null) {
            postgres.stop();
        } else if (ADMIN_URL != null && !ADMIN_URL.isBlank()) {
            admin("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    @Test
    void holdsAndReleasesOnceAcrossDuplicateAndDifferentTerminalEvents() {
        Fixture fixture = seed();
        var created = created(fixture);
        auctionEvents.receive(created);
        auctionEvents.receive(created);
        auctionEvents.receive(created(fixture));
        assertBalance(fixture, 4, 1, 0);
        assertThat(hold(fixture)).containsEntry("order_id", null).containsEntry("status", "HELD");
        assertThat(movements(fixture)).containsExactly("RESERVE");

        auctionEvents.receive(auction(fixture, "AuctionCancelled", Map.of()));
        auctionEvents.receive(auction(fixture, "AuctionFailed", Map.of()));
        assertBalance(fixture, 5, 0, 0);
        assertThat(hold(fixture)).containsEntry("status", "RELEASED");
        assertThat(movements(fixture)).containsExactly("RESERVE", "RELEASE");
        assertThat(inventoryEvents(fixture)).containsExactly("InventoryReserved", "InventoryReleased");
    }

    @Test
    void linksWithoutMovingStockAndOnlyTheWinnerCanReadTheWarehouse() throws Exception {
        Fixture fixture = seed();
        auctionEvents.receive(created(fixture));
        auctionEvents.receive(auction(fixture, "AuctionWon", Map.of("winnerId", fixture.buyer())));
        when(decoder.decode("winner")).thenReturn(buyerJwt("winner", fixture.buyer()));
        when(decoder.decode("stranger")).thenReturn(buyerJwt("stranger", UUID.randomUUID()));
        var mvc = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();

        mvc.perform(get(PATH).param("orderId", fixture.order().toString())
                .header("Authorization", "Bearer winner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        var settled = auction(fixture, "AuctionSettled", Map.of("orderId", fixture.order()));
        auctionEvents.receive(settled);
        auctionEvents.receive(settled);
        assertBalance(fixture, 4, 1, 0);
        assertThat(hold(fixture)).containsEntry("order_id", fixture.order()).containsEntry("status", "HELD");
        assertThat(movements(fixture)).containsExactly("RESERVE");
        assertThat(inventoryEvents(fixture)).containsExactly("InventoryReserved");
        mvc.perform(get(PATH).param("orderId", fixture.order().toString())
                .header("Authorization", "Bearer winner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reservationId").value(hold(fixture).get("id").toString()))
                .andExpect(jsonPath("$.data[0].warehouseId").value(fixture.warehouse().toString()))
                .andExpect(jsonPath("$.data[0].status").value("HELD"));
        mvc.perform(get(PATH).param("orderId", fixture.order().toString())
                .header("Authorization", "Bearer stranger"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());

        order(fixture, "OrderPaid");
        order(fixture, "OrderPaid");
        assertBalance(fixture, 4, 0, 1);
        assertThat(movements(fixture)).containsExactly("RESERVE", "COMMIT");
        assertThat(inventoryEvents(fixture)).containsExactly("InventoryReserved", "InventoryCommitted");
    }

    @Test
    void expirySkipsAuctionHoldsAndOrderCancelledReturnsStock() {
        Fixture fixture = seed();
        auctionEvents.receive(created(fixture));
        jdbc.update("UPDATE inventory_reservations SET expires_at = now() - interval '1 hour' WHERE auction_id = ?",
                fixture.auction());
        expiry.runOnce();
        assertBalance(fixture, 4, 1, 0);
        auctionEvents.receive(auction(fixture, "AuctionSettled", Map.of("orderId", fixture.order())));
        order(fixture, "OrderCancelled");
        assertBalance(fixture, 5, 0, 0);
        assertThat(movements(fixture)).containsExactly("RESERVE", "RELEASE");
    }

    @Test
    void checkoutReleaseRestoresStockOnceOnEventReplay() {
        Fixture fixture = seed();
        reservations.reserve(new ReserveRequest(UUID.randomUUID(),
                        List.of(new ReserveRequest.Item(fixture.order(), fixture.sku(), 1))),
                CurrentUser.from(buyerJwt("checkout-release", fixture.buyer())));
        assertBalance(fixture, 4, 1, 0);
        String orderId = fixture.order().toString();
        JsonNode payload = JsonMapper.shared().valueToTree(Map.of("orderId", orderId));
        var event = new EventEnvelope<>(UUID.randomUUID(), "CheckoutStockReleaseRequested", orderId,
                Instant.now(), TRACE_ID, payload);
        orderEvents.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0, orderId, event));
        orderEvents.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 1, orderId, event));

        assertBalance(fixture, 5, 0, 0);
        assertThat(jdbc.queryForObject("SELECT status::text FROM inventory_reservations WHERE order_id = ?",
                String.class, fixture.order())).isEqualTo("RELEASED");
        assertThat(movements(fixture)).containsExactly("RESERVE", "RELEASE");
    }

    @Test
    void stockLostAfterPrecheckPublishesOneRejectionAndProcessesTheEvent() {
        Fixture fixture = seed();
        jdbc.update("""
                UPDATE inventory_balances SET available_quantity = 0, unavailable_quantity = 5 WHERE sku_id = ?
                """, fixture.sku());
        var created = created(fixture);
        auctionEvents.receive(created);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations WHERE auction_id = ?",
                Integer.class, fixture.auction())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                created.eventId())).isEqualTo(1);
        assertThat(movements(fixture)).isEmpty();
        assertThat(inventoryEvents(fixture)).containsExactly("AuctionStockRejected");
        assertThat(jdbc.queryForMap("""
                SELECT aggregate_id, topic, payload->'payload'->>'skuId' AS sku_id,
                       payload->'payload'->>'reason' AS reason
                FROM outbox WHERE event_type = 'AuctionStockRejected' AND aggregate_id = ?
                """, fixture.auction().toString())).containsEntry("topic", Topics.INVENTORY)
                .containsEntry("aggregate_id", fixture.auction().toString())
                .containsEntry("sku_id", fixture.sku().toString()).containsEntry("reason", "OUT_OF_STOCK");
        jdbc.update("""
                UPDATE inventory_balances SET available_quantity = 5, unavailable_quantity = 0 WHERE sku_id = ?
                """, fixture.sku());
        auctionEvents.receive(created);
        assertBalance(fixture, 5, 0, 0);
        assertThat(inventoryEvents(fixture)).containsExactly("AuctionStockRejected");
    }

    private Fixture seed() {
        UUID warehouse = jdbc.queryForObject("""
                INSERT INTO warehouses (code, name) VALUES ('WH_001', 'Default')
                ON CONFLICT (code) DO UPDATE SET code = EXCLUDED.code RETURNING id
                """, UUID.class);
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, 5, 5)
                """, sku, warehouse);
        return new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sku, warehouse);
    }

    private Map<String, Object> hold(Fixture fixture) {
        return jdbc.queryForMap("""
                SELECT id, order_id, status::text AS status FROM inventory_reservations WHERE auction_id = ?
                """, fixture.auction());
    }

    private void assertBalance(Fixture fixture, int available, int reserved, int unavailable) {
        assertThat(jdbc.queryForMap("""
                SELECT available_quantity, reserved_quantity, unavailable_quantity
                FROM inventory_balances WHERE sku_id = ?
                """, fixture.sku())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "available_quantity", available, "reserved_quantity", reserved, "unavailable_quantity", unavailable));
    }

    private List<String> movements(Fixture fixture) {
        return jdbc.queryForList("""
                SELECT movement_type::text FROM inventory_movements WHERE sku_id = ? ORDER BY created_at, id
                """, String.class, fixture.sku());
    }

    private List<String> inventoryEvents(Fixture fixture) {
        return jdbc.queryForList("""
                SELECT event_type FROM outbox WHERE payload->'payload'->>'auctionId' = ? ORDER BY occurred_at, id
                """, String.class, fixture.auction().toString());
    }

    private EventEnvelope<JsonNode> created(Fixture fixture) {
        return auction(fixture, "AuctionCreated", Map.of("skuId", fixture.sku(), "quantity", 1,
                "endAt", Instant.now().plusSeconds(3600).toString()));
    }

    private EventEnvelope<JsonNode> auction(Fixture fixture, String type, Map<String, Object> fields) {
        ObjectNode payload = JsonMapper.shared().valueToTree(fields);
        payload.put("auctionId", fixture.auction().toString());
        return new EventEnvelope<>(UUID.randomUUID(), type, fixture.auction().toString(), Instant.now(), TRACE_ID, payload);
    }

    private void order(Fixture fixture, String type) {
        JsonNode payload = JsonMapper.shared().valueToTree(Map.of("orderId", fixture.order(), "orderType", "AUCTION",
                "referenceId", fixture.auction(), "reason", "PAYMENT_DEADLINE_EXPIRED"));
        var event = new EventEnvelope<>(UUID.randomUUID(), type, fixture.order().toString(), Instant.now(), TRACE_ID, payload);
        orderEvents.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0, fixture.order().toString(), event));
    }

    private static Jwt buyerJwt(String token, UUID buyer) {
        return Jwt.withTokenValue(token).header("alg", "RS256").subject(buyer.toString())
                .claim("roles", List.of("BUYER")).build();
    }

    private static void admin(String query) throws SQLException {
        try (var connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(query);
        }
    }

    private record Fixture(UUID auction, UUID order, UUID buyer, UUID sku, UUID warehouse) {
    }
}
