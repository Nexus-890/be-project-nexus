package com.group5.inventoryservice.inventory;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-intake-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_IT",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class InventoryIntakeIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static { POSTGRES.start(); }

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID BUYER_ID = UUID.randomUUID();
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryMovementRepository movements;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private UUID defaultWarehouse;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_IT', 'Intake test') ON CONFLICT (code) DO NOTHING");
        defaultWarehouse = warehouseId("WH_IT");
        reset(decoder);
        when(decoder.decode("admin")).thenReturn(token("admin", ADMIN_ID, "ADMIN"));
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER_ID, "BUYER"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void intakeWithoutWarehouseUsesTheConfiguredDefaultWarehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.skuId").value(sku.toString()))
                .andExpect(jsonPath("$.data.warehouseId").value(defaultWarehouse.toString()))
                .andExpect(jsonPath("$.data.total").value(100))
                .andExpect(jsonPath("$.data.available").value(100))
                .andExpect(jsonPath("$.data.reserved").value(0))
                .andExpect(jsonPath("$.data.unavailable").value(0));

        Map<String, Object> row = row(sku, defaultWarehouse);
        assertThat(row).containsEntry("total_quantity", 100).containsEntry("available_quantity", 100)
                .containsEntry("reserved_quantity", 0).containsEntry("unavailable_quantity", 0)
                .containsEntry("warehouse_id", defaultWarehouse)
                .containsEntry("created_by", ADMIN_ID).containsEntry("updated_by", ADMIN_ID);
        assertThat(defaultWarehouse).isNotEqualTo(warehouseId("WH_001"));
    }

    @Test
    void flywayAddsIntakeAndCommitToTheMovementType() {
        String values = jdbc.queryForObject("SELECT enum_range(NULL::inventory_movement_type)::text", String.class);
        assertThat(values).contains("INTAKE", "COMMIT", "RESERVE", "RELEASE");
    }

    @Test
    void eachIntakeWritesOneIntakeMovementWhoseSumMatchesTheTotal() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100)).andExpect(status().isCreated());
        intake("admin", body(sku, null, 50)).andExpect(status().isCreated());

        UUID balanceId = (UUID) row(sku, defaultWarehouse).get("id");
        List<InventoryMovement> ledger = movements.findBySkuIdAndWarehouseIdOrderByCreatedAtAscIdAsc(sku,
                defaultWarehouse);
        assertThat(ledger).extracting(InventoryMovement::getMovementType)
                .containsExactly(MovementType.INTAKE, MovementType.INTAKE);
        assertThat(ledger).extracting(InventoryMovement::getQuantity).containsExactly(100, 50);
        assertThat(ledger).extracting(InventoryMovement::getReferenceId).containsOnly(balanceId);
        assertThat(ledger).extracting(InventoryMovement::getCreatedBy).containsOnly(ADMIN_ID);
        assertThat(ledger).allSatisfy(movement -> assertThat(movement.getCreatedAt()).isNotNull());
        assertThat(intakeSum(sku)).isEqualTo(row(sku, defaultWarehouse).get("total_quantity"));
    }

    @Test
    void secondIntakeRaisesTotalAndAvailableOnTheSameRow() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100)).andExpect(status().isCreated());
        intake("admin", body(sku, null, 50))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total").value(150))
                .andExpect(jsonPath("$.data.available").value(150));

        assertThat(rowCount(sku)).isEqualTo(1);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 150)
                .containsEntry("available_quantity", 150).containsEntry("version", 1L);
    }

    @RepeatedTest(5)
    void concurrentFirstIntakesLeaveOneRowWithTheSum() throws Exception {
        UUID sku = UUID.randomUUID();
        int callers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 1; i <= callers; i++) {
            int quantity = i;
            calls.add(() -> {
                start.await();
                return intake("admin", body(sku, null, quantity)).andReturn().getResponse().getStatus();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = calls.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowCount(sku)).isEqualTo(1);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 55)
                .containsEntry("available_quantity", 55);
        assertThat(movementCount(sku)).isEqualTo(callers);
        assertThat(intakeSum(sku)).isEqualTo(55);
    }

    @Test
    void buyerGets403AndAnonymousGets401WithoutTouchingStock() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("buyer", body(sku, null, 100))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/inventory/intake").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sku, null, 100)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        assertThat(rowCount(sku)).isZero();
        assertThat(movementCount(sku)).isZero();
    }

    @Test
    void nonPositiveQuantityReturns400AndTheRowIsUnchanged() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100)).andExpect(status().isCreated());
        Map<String, Object> before = row(sku, defaultWarehouse);

        for (int quantity : new int[] {-5, 0}) {
            intake("admin", body(sku, null, quantity))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        }
        assertThat(row(sku, defaultWarehouse)).isEqualTo(before);
        assertThat(movementCount(sku)).isEqualTo(1);
        assertThat(intakeSum(sku)).isEqualTo(100);
    }

    @Test
    void explicitWarehouseIsUsedAndAnUnknownOneReturns404() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID main = warehouseId("WH_001");
        intake("admin", body(sku, main, 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warehouseId").value(main.toString()));
        assertThat(row(sku, main)).containsEntry("total_quantity", 20);

        intake("admin", body(sku, UUID.randomUUID(), 20))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_FOUND"));
        assertThat(rowCount(sku)).isEqualTo(1);
        assertThat(movementCount(sku)).isEqualTo(1);
    }

    private ResultActions intake(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/inventory/intake").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(UUID sku, UUID warehouse, int quantity) {
        String warehouseField = warehouse == null ? "" : ",\"warehouseId\":\"" + warehouse + "\"";
        return "{\"skuId\":\"" + sku + "\"" + warehouseField + ",\"quantity\":" + quantity + "}";
    }

    private UUID warehouseId(String code) {
        return jdbc.queryForObject("SELECT id FROM warehouses WHERE code = ?", UUID.class, code);
    }

    private Map<String, Object> row(UUID sku, UUID warehouse) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?", sku, warehouse);
    }

    private int rowCount(UUID sku) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_balances WHERE sku_id = ?", Integer.class, sku);
    }

    private int movementCount(UUID sku) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE sku_id = ?", Integer.class, sku);
    }

    private Integer intakeSum(UUID sku) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0)::int FROM inventory_movements
                WHERE sku_id = ? AND movement_type = 'INTAKE'
                """, Integer.class, sku);
    }

    private static Jwt token(String value, UUID userId, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
