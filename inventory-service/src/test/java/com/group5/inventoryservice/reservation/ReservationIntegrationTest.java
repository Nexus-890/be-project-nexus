package com.group5.inventoryservice.reservation;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-reservation-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_RES",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class ReservationIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    static { POSTGRES.start(); }

    private static final String PATH = "/api/v1/inventory/reservations";
    private static final UUID BUYER_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private UUID warehouse;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_RES', 'Reservation test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_RES'", UUID.class);
        reset(decoder);
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER_ID, "BUYER"));
        when(decoder.decode("seller")).thenReturn(token("seller", SELLER_ID, "SELLER"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void reservingTwoOfTenReturns201AndMovesTwoFromAvailableToReserved() throws Exception {
        UUID sku = stock(10);
        UUID order = UUID.randomUUID();

        String response = reserve("buyer", body(item(order, sku, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reservationIds.length()").value(1))
                .andExpect(jsonPath("$.data.warehouseId").value(warehouse.toString()))
                .andReturn().getResponse().getContentAsString();

        assertThat(balance(sku)).containsEntry("total_quantity", 10).containsEntry("available_quantity", 8)
                .containsEntry("reserved_quantity", 2).containsEntry("unavailable_quantity", 0)
                .containsEntry("version", 1L).containsEntry("updated_by", BUYER_ID);
        UUID reservationId = UUID.fromString(JsonPath.read(response, "$.data.reservationIds[0]"));
        Map<String, Object> reservation = reservation(reservationId);
        assertThat(reservation).containsEntry("order_id", order).containsEntry("sku_id", sku)
                .containsEntry("warehouse_id", warehouse).containsEntry("quantity", 2)
                .containsEntry("status", "HELD").containsEntry("created_by", BUYER_ID);
        assertThat(reservation.get("cart_id")).isNull();

        OffsetDateTime expiresAt = OffsetDateTime.parse(JsonPath.read(response, "$.data.expiresAt"));
        assertThat(expiresAt.getOffset().getTotalSeconds()).isZero();
        assertThat(expiresAt.toInstant()).isCloseTo(Instant.now().plus(Duration.ofMinutes(30)),
                within(Duration.ofSeconds(5)));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM inventory_reservations WHERE id = ?",
                OffsetDateTime.class, reservationId).toInstant()).isEqualTo(expiresAt.toInstant());

        assertThat(jdbc.queryForList("""
                SELECT movement_type::text AS type, quantity, reference_id, created_by
                FROM inventory_movements WHERE sku_id = ?
                """, sku)).containsExactly(Map.of("type", "RESERVE", "quantity", 2,
                "reference_id", reservationId, "created_by", BUYER_ID));
    }

    @Test
    void reservingTwoWithOneAvailableReturns409WithTheNumbersAndLeavesTheRowAsItWas() throws Exception {
        UUID sku = stock(1);
        Map<String, Object> before = balance(sku);

        reserve("buyer", body(item(UUID.randomUUID(), sku, 2)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVENTORY_INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.skuId").value(sku.toString()))
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.requested").value(2));

        assertThat(balance(sku)).isEqualTo(before);
        assertNothingReserved();
    }

    @Test
    void threeItemsWhereTheSecondLacksStockReserveNoneOfThem() throws Exception {
        UUID first = stock(10);
        UUID second = stock(1);
        UUID third = stock(10);
        List<Map<String, Object>> before = List.of(balance(first), balance(second), balance(third));
        UUID order = UUID.randomUUID();

        reserve("buyer", body(item(order, first, 2), item(order, second, 2), item(order, third, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.skuId").value(second.toString()))
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.requested").value(2));

        assertThat(List.of(balance(first), balance(second), balance(third))).isEqualTo(before);
        assertNothingReserved();
    }

    @Test
    void eachReservationKeepsTheOrderIdOfItsOwnItem() throws Exception {
        UUID firstSku = stock(10);
        UUID secondSku = stock(10);
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();

        String response = reserve("buyer", body(item(firstOrder, firstSku, 1), item(secondOrder, secondSku, 3)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(response, "$.data.reservationIds");
        assertThat(ids).hasSize(2);
        assertThat(reservation(UUID.fromString(ids.get(0)))).containsEntry("order_id", firstOrder)
                .containsEntry("sku_id", firstSku).containsEntry("quantity", 1);
        assertThat(reservation(UUID.fromString(ids.get(1)))).containsEntry("order_id", secondOrder)
                .containsEntry("sku_id", secondSku).containsEntry("quantity", 3);
        assertThat(jdbc.queryForList("SELECT DISTINCT warehouse_id FROM inventory_reservations", UUID.class))
                .containsExactly(warehouse);
        assertThat(jdbc.queryForList("SELECT DISTINCT expires_at FROM inventory_reservations")).hasSize(1);
        assertThat(movementCount("RESERVE")).isEqualTo(2);
    }

    @Test
    void twoOrdersWhereTheSecondOrdersItemLacksStockReserveNothingForEither() throws Exception {
        UUID firstSku = stock(10);
        UUID secondSku = stock(1);
        List<Map<String, Object>> before = List.of(balance(firstSku), balance(secondSku));

        reserve("buyer", body(item(UUID.randomUUID(), firstSku, 2), item(UUID.randomUUID(), secondSku, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.skuId").value(secondSku.toString()))
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.requested").value(5));

        assertThat(List.of(balance(firstSku), balance(secondSku))).isEqualTo(before);
        assertNothingReserved();
    }

    @Test
    void theSameSkuInTwoOrdersIsCheckedAgainstTheirSum() throws Exception {
        UUID sku = stock(3);
        Map<String, Object> before = balance(sku);

        reserve("buyer", body(item(UUID.randomUUID(), sku, 2), item(UUID.randomUUID(), sku, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.requested").value(4));

        assertThat(balance(sku)).isEqualTo(before);
        assertNothingReserved();
    }

    @Test
    void aSkuWithNoStockRowReturns409WithZeroAvailable() throws Exception {
        UUID sku = UUID.randomUUID();

        reserve("buyer", body(item(UUID.randomUUID(), sku, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.skuId").value(sku.toString()))
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.requested").value(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_balances", Integer.class)).isZero();
        assertNothingReserved();
    }

    @Test
    void sendingTheSameRequestAgainReturnsTheSameReservationsWithoutReservingTwice() throws Exception {
        UUID sku = stock(10);
        String body = body(item(UUID.randomUUID(), sku, 2));

        String first = reserve("buyer", body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String second = reserve("buyer", body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();

        assertThat((Object) JsonPath.read(second, "$.data")).isEqualTo(JsonPath.read(first, "$.data"));
        assertThat(balance(sku)).containsEntry("available_quantity", 8).containsEntry("reserved_quantity", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isEqualTo(1);
        assertThat(movementCount("RESERVE")).isEqualTo(1);
    }

    @Test
    void aRepeatWithADifferentQuantityReturns409AndReservesNothingMore() throws Exception {
        UUID sku = stock(10);
        UUID order = UUID.randomUUID();
        reserve("buyer", body(item(order, sku, 2))).andExpect(status().isCreated());
        Map<String, Object> before = balance(sku);

        reserve("buyer", body(item(order, sku, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_RESERVATION_CONFLICT"));
        reserve("buyer", body(item(order, sku, 2), item(order, stock(10), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_RESERVATION_CONFLICT"));

        assertThat(balance(sku)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isEqualTo(1);
        assertThat(movementCount("RESERVE")).isEqualTo(1);
    }

    @Test
    void aRepeatAfterTheReservationLeftHeldReturns409AndChangesNothing() throws Exception {
        UUID sku = stock(10);
        String body = body(item(UUID.randomUUID(), sku, 2));
        reserve("buyer", body).andExpect(status().isCreated());

        for (String status : new String[] {"RELEASED", "EXPIRED", "CONFIRMED"}) {
            jdbc.update("UPDATE inventory_reservations SET status = CAST(? AS inventory_reservation_status)", status);
            Map<String, Object> before = balance(sku);

            reserve("buyer", body)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVENTORY_RESERVATION_CONFLICT"));

            assertThat(balance(sku)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isEqualTo(1);
            assertThat(movementCount("RESERVE")).isEqualTo(1);
        }
    }

    @Test
    void theLargestQuantitiesOfOneSkuAreSummedWithoutOverflowAndCannotFreeOtherReservations() throws Exception {
        UUID sku = stock(10);
        reserve("buyer", body(item(UUID.randomUUID(), sku, 2))).andExpect(status().isCreated());
        Map<String, Object> before = balance(sku);

        reserve("buyer", body(item(UUID.randomUUID(), sku, 999), item(UUID.randomUUID(), sku, 999)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.available").value(8))
                .andExpect(jsonPath("$.requested").value(1998));
        reserve("buyer", body(item(UUID.randomUUID(), sku, Integer.MAX_VALUE),
                item(UUID.randomUUID(), sku, Integer.MAX_VALUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(balance(sku)).isEqualTo(before).containsEntry("available_quantity", 8)
                .containsEntry("reserved_quantity", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameOrderAndSkuTwiceInOneRequestReturns400() throws Exception {
        UUID sku = stock(10);
        UUID order = UUID.randomUUID();
        Map<String, Object> before = balance(sku);

        reserve("buyer", body(item(order, sku, 1), item(order, sku, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVENTORY_DUPLICATE_RESERVATION_ITEM"));

        assertThat(balance(sku)).isEqualTo(before);
        assertNothingReserved();
    }

    @Test
    void invalidBodiesReturn400ValidationProblems() throws Exception {
        UUID sku = stock(10);
        Map<String, Object> before = balance(sku);

        reserve("buyer", "{\"orderGroupId\":\"" + UUID.randomUUID() + "\",\"items\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("items"));
        reserve("buyer", "{\"items\":[" + item(UUID.randomUUID(), sku, 1) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("orderGroupId"));
        for (int quantity : new int[] {0, -2, 1000, Integer.MAX_VALUE}) {
            reserve("buyer", body(item(UUID.randomUUID(), sku, quantity)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("items[0].quantity"));
        }
        String[] tooMany = new String[101];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = item(UUID.randomUUID(), sku, 1);
        }
        reserve("buyer", body(tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("items"));

        assertThat(balance(sku)).isEqualTo(before);
        assertNothingReserved();
    }

    @Test
    void aSellerGets403AndNoTokenGets401WithoutTouchingStock() throws Exception {
        UUID sku = stock(10);
        Map<String, Object> before = balance(sku);
        String body = body(item(UUID.randomUUID(), sku, 1));

        reserve("seller", body)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        assertThat(balance(sku)).isEqualTo(before);
        assertNothingReserved();
    }

    @Test
    void flywayAddsThePartialUniqueIndexOnOrderSkuAndWarehouse() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_reservation_order_item'", String.class);
        assertThat(definition).contains("UNIQUE", "(order_id, sku_id, warehouse_id)", "WHERE (order_id IS NOT NULL)");
    }

    private ResultActions reserve(String token, String body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String... items) {
        return "{\"orderGroupId\":\"" + UUID.randomUUID() + "\",\"items\":["
                + String.join(",", items) + "]}";
    }

    private static String item(UUID order, UUID sku, int quantity) {
        return "{\"orderId\":\"" + order + "\",\"skuId\":\"" + sku + "\",\"quantity\":" + quantity + "}";
    }

    private UUID stock(int available) {
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, ?, ?)
                """, sku, warehouse, available, available);
        return sku;
    }

    private Map<String, Object> balance(UUID sku) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?",
                sku, warehouse);
    }

    private Map<String, Object> reservation(UUID id) {
        return jdbc.queryForMap("""
                SELECT order_id, sku_id, warehouse_id, quantity, status::text AS status, cart_id, created_by
                FROM inventory_reservations WHERE id = ?
                """, id);
    }

    private void assertNothingReserved() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isZero();
        assertThat(movementCount("RESERVE")).isZero();
    }

    private int movementCount(String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE movement_type = CAST(? AS inventory_movement_type)",
                Integer.class, type);
    }

    private static Jwt token(String value, UUID userId, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
