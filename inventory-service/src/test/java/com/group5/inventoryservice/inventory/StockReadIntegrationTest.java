package com.group5.inventoryservice.inventory;

import jakarta.servlet.Filter;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-stock-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_STOCK",
        "nexus.security.public-endpoints[0].method=" + StockReadIntegrationTest.BATCH_METHOD,
        "nexus.security.public-endpoints[0].path=" + StockReadIntegrationTest.BATCH_PATH,
        "nexus.security.public-endpoints[1].method=" + StockReadIntegrationTest.SINGLE_METHOD,
        "nexus.security.public-endpoints[1].path=" + StockReadIntegrationTest.SINGLE_PATH,
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.group5.inventoryservice.inventory.StockReadIntegrationTest$SqlCapture"
})
class StockReadIntegrationTest {
    static final String BATCH_METHOD = "GET";
    static final String BATCH_PATH = "/api/v1/inventory";
    static final String SINGLE_METHOD = "GET";
    static final String SINGLE_PATH = "/api/v1/inventory/{skuId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
            + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static { POSTGRES.start(); }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private UUID defaultWarehouse;
    private UUID otherWarehouse;

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
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_STOCK', 'Stock test') ON CONFLICT (code) DO NOTHING");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_OTHER', 'Other') ON CONFLICT (code) DO NOTHING");
        defaultWarehouse = warehouseId("WH_STOCK");
        otherWarehouse = warehouseId("WH_OTHER");
        reset(decoder);
        when(decoder.decode("buyer")).thenReturn(token());
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        SqlCapture.STATEMENTS.clear();
    }

    @Test
    void aSkuWithStockReturnsItsAvailableQuantityWithoutAToken() throws Exception {
        UUID sku = UUID.randomUUID();
        balance(sku, defaultWarehouse, 100, 97, 3);

        mvc.perform(get("/api/v1/inventory/" + sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.skuId").value(sku.toString()))
                .andExpect(jsonPath("$.data.available").value(97));
    }

    @Test
    void aSkuWithNoBalanceRowReturns200WithAvailableZero() throws Exception {
        UUID sku = UUID.randomUUID();

        mvc.perform(get("/api/v1/inventory/" + sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(sku.toString()))
                .andExpect(jsonPath("$.data.available").value(0));
    }

    @Test
    void theResponseExposesAvailableOnlyNeverReservedOrTotal() throws Exception {
        UUID sku = UUID.randomUUID();
        balance(sku, defaultWarehouse, 10, 4, 6);

        mvc.perform(get("/api/v1/inventory/" + sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data.available").value(4))
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.reserved").doesNotExist())
                .andExpect(jsonPath("$.data.unavailable").doesNotExist());
    }

    @Test
    void onlyTheDefaultWarehouseIsRead() throws Exception {
        UUID sku = UUID.randomUUID();
        balance(sku, otherWarehouse, 50, 50, 0);

        mvc.perform(get("/api/v1/inventory/" + sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(0));
    }

    @Test
    void aSkuIdThatIsNotAUuidReturns400() throws Exception {
        mvc.perform(get("/api/v1/inventory/not-a-uuid").header("Authorization", "Bearer buyer"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private void balance(UUID sku, UUID warehouse, int total, int available, int reserved) {
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity,
                                                reserved_quantity)
                VALUES (?, ?, ?, ?, ?)
                """, sku, warehouse, total, available, reserved);
    }

    private UUID warehouseId(String code) {
        return jdbc.queryForObject("SELECT id FROM warehouses WHERE code = ?", UUID.class, code);
    }

    private static Jwt token() {
        return Jwt.withTokenValue("buyer").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("BUYER")).claim("privileges", List.of()).build();
    }

    public static class SqlCapture implements StatementInspector {
        static final ConcurrentLinkedQueue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }
}
