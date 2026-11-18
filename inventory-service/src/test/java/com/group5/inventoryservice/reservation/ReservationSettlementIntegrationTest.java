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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        "nexus.inventory.default-warehouse-code=WH_SETTLE",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class ReservationSettlementIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    static { POSTGRES.start(); }

    private static final String PATH = "/api/v1/inventory/reservations";
    private static final UUID BUYER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
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
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_SETTLE', 'Settlement test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_SETTLE'", UUID.class);
        reset(decoder);
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER_ID, "BUYER"));
        when(decoder.decode("admin")).thenReturn(token("admin", ADMIN_ID, "ADMIN"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void confirmingTwoHeldUnitsAtAvailable8Reserved2Leaves8Available0Reserved2Unavailable() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);
        assertBalance(sku, 10, 8, 2, 0);

        settle("admin", reservation, "confirm").andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertBalance(sku, 10, 8, 0, 2);
        assertThat(balance(sku)).containsEntry("updated_by", ADMIN_ID).containsEntry("version", 2L);
        assertThat(reservation(reservation)).containsEntry("status", "CONFIRMED")
                .containsEntry("updated_by", ADMIN_ID);
        assertThat(reservation(reservation).get("released_at")).isNull();
        assertThat(movements(sku, "COMMIT")).containsExactly(Map.of("quantity", 2,
                "reference_id", reservation, "created_by", ADMIN_ID));
    }

    @Test
    void releasingTwoHeldUnitsAtAvailable8Reserved2Leaves10Available0Reserved0Unavailable() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);

        settle("admin", reservation, "release").andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertBalance(sku, 10, 10, 0, 0);
        assertThat(reservation(reservation)).containsEntry("status", "RELEASED");
        assertThat(reservation(reservation).get("released_at")).isNotNull();
        assertThat(movements(sku, "RELEASE")).containsExactly(Map.of("quantity", 2,
                "reference_id", reservation, "created_by", ADMIN_ID));
    }

    @Test
    void confirmingTwiceLeavesTheBalanceOfOneCallAndOneCommitRow() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);
        settle("admin", reservation, "confirm").andExpect(status().isNoContent());
        Map<String, Object> afterFirst = balance(sku);

        settle("admin", reservation, "confirm").andExpect(status().isNoContent());

        assertThat(balance(sku)).isEqualTo(afterFirst);
        assertThat(movements(sku, "COMMIT")).hasSize(1);
    }

    @Test
    void releasingTwiceLeavesTheBalanceOfOneCallAndOneReleaseRow() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);
        settle("admin", reservation, "release").andExpect(status().isNoContent());
        Map<String, Object> afterFirst = balance(sku);
        Map<String, Object> reservationAfterFirst = reservation(reservation);

        settle("admin", reservation, "release").andExpect(status().isNoContent());

        assertThat(balance(sku)).isEqualTo(afterFirst);
        assertThat(reservation(reservation)).isEqualTo(reservationAfterFirst);
        assertThat(movements(sku, "RELEASE")).hasSize(1);
    }

    @Test
    void releasingAConfirmedReservationReturns409AndLeavesTheBalanceUnchanged() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);
        settle("admin", reservation, "confirm").andExpect(status().isNoContent());
        Map<String, Object> before = balance(sku);

        settle("admin", reservation, "release")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVENTORY_ILLEGAL_TRANSITION"))
                .andExpect(jsonPath("$.instance").value(PATH + "/" + reservation + "/release"));

        assertThat(balance(sku)).isEqualTo(before);
        assertThat(reservation(reservation)).containsEntry("status", "CONFIRMED");
        assertThat(movements(sku, "RELEASE")).isEmpty();
    }

    @Test
    void confirmingAReleasedReservationReturns409AndLeavesTheBalanceUnchanged() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);
        settle("admin", reservation, "release").andExpect(status().isNoContent());
        Map<String, Object> before = balance(sku);

        settle("admin", reservation, "confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ILLEGAL_TRANSITION"));

        assertThat(balance(sku)).isEqualTo(before);
        assertThat(reservation(reservation)).containsEntry("status", "RELEASED");
        assertThat(movements(sku, "COMMIT")).isEmpty();
    }

    @Test
    void anExpiredReservationCannotBeConfirmedAndReleasingItChangesNothing() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);
        jdbc.update("UPDATE inventory_reservations SET status = 'EXPIRED' WHERE id = ?", reservation);
        Map<String, Object> before = balance(sku);

        settle("admin", reservation, "confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ILLEGAL_TRANSITION"));
        settle("admin", reservation, "release").andExpect(status().isNoContent());

        assertThat(balance(sku)).isEqualTo(before);
        assertThat(reservation(reservation)).containsEntry("status", "EXPIRED");
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void onlyOneOfManyParallelCallsChangesTheStock() throws Exception {
        UUID sku = stock(10);
        UUID confirmed = reserve(sku, 2);
        UUID contested = reserve(sku, 3);

        List<Integer> sameCall = parallel(List.of("confirm", "confirm", "confirm", "confirm", "confirm", "confirm"),
                confirmed);
        List<Integer> mixed = parallel(List.of("confirm", "release", "confirm", "release", "confirm", "release"),
                contested);

        assertThat(sameCall).containsOnly(204);
        assertThat(movements(sku, "COMMIT")).extracting(row -> row.get("reference_id")).containsOnlyOnce(confirmed);
        String winner = (String) reservation(contested).get("status");
        int expectedConflicts = 3;
        assertThat(mixed).filteredOn(code -> code == 409).hasSize(expectedConflicts);
        assertThat(mixed).filteredOn(code -> code == 204).hasSize(3);
        if (winner.equals("CONFIRMED")) {
            assertBalance(sku, 10, 5, 0, 5);
        } else {
            assertThat(winner).isEqualTo("RELEASED");
            assertBalance(sku, 10, 8, 0, 2);
        }
        assertThat(movementCount()).isEqualTo(4);
    }

    @Test
    void unknownIdsMalformedIdsOtherRolesAndNoTokenChangeNothing() throws Exception {
        UUID sku = stock(10);
        UUID reservation = reserve(sku, 2);
        Map<String, Object> before = balance(sku);

        for (String action : List.of("confirm", "release")) {
            settle("admin", UUID.randomUUID(), action)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVENTORY_RESERVATION_NOT_FOUND"));
            mvc.perform(post(PATH + "/not-a-uuid/" + action).header("Authorization", "Bearer admin"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.errors[0].field").value("id"));
            settle("buyer", reservation, action)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(post(PATH + "/" + reservation + "/" + action))
                    .andExpect(status().isUnauthorized());
        }

        assertThat(balance(sku)).isEqualTo(before);
        assertThat(reservation(reservation)).containsEntry("status", "HELD");
        assertThat(movementCount()).isEqualTo(1);
    }

    private List<Integer> parallel(List<String> actions, UUID reservation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String action : actions) {
                Callable<Integer> call = () -> {
                    start.await();
                    return settle("admin", reservation, action).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> result : results) {
                codes.add(result.get(30, TimeUnit.SECONDS));
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions settle(String token, UUID reservation, String action) throws Exception {
        return mvc.perform(post(PATH + "/" + reservation + "/" + action).header("Authorization", "Bearer " + token));
    }

    private UUID reserve(UUID sku, int quantity) throws Exception {
        String body = "{\"orderGroupId\":\"" + UUID.randomUUID() + "\",\"items\":[{\"orderId\":\""
                + UUID.randomUUID() + "\",\"skuId\":\"" + sku + "\",\"quantity\":" + quantity + "}]}";
        String response = mvc.perform(post(PATH).header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.data.reservationIds[0]"));
    }

    private UUID stock(int available) {
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, ?, ?)
                """, sku, warehouse, available, available);
        return sku;
    }

    private void assertBalance(UUID sku, int total, int available, int reserved, int unavailable) {
        assertThat(balance(sku)).containsEntry("total_quantity", total).containsEntry("available_quantity", available)
                .containsEntry("reserved_quantity", reserved).containsEntry("unavailable_quantity", unavailable);
    }

    private Map<String, Object> balance(UUID sku) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?",
                sku, warehouse);
    }

    private Map<String, Object> reservation(UUID id) {
        return jdbc.queryForMap("""
                SELECT status::text AS status, released_at, updated_by
                FROM inventory_reservations WHERE id = ?
                """, id);
    }

    private List<Map<String, Object>> movements(UUID sku, String type) {
        return jdbc.queryForList("""
                SELECT quantity, reference_id, created_by FROM inventory_movements
                WHERE sku_id = ? AND movement_type = CAST(? AS inventory_movement_type)
                """, sku, type);
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class);
    }

    private static Jwt token(String value, UUID userId, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
