package com.group5.inventoryservice.warehouse.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/warehouse-management-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_MGMT",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class WarehouseManagementIntegrationTest {
    private static final String PATH = "/api/v1/warehouses";
    private static final String INTAKE_PATH = "/api/v1/inventory/intake";
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID CREATOR_ID = UUID.randomUUID();
    static { POSTGRES.start(); }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private com.group5.inventoryservice.shared.catalog.CatalogSkuOwners skuOwners;
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
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM warehouses");
        defaultWarehouse = insertWarehouse("WH_MGMT", "ACTIVE");
        reset(decoder);
        when(decoder.decode("admin")).thenReturn(token("admin", ADMIN_ID, "ADMIN"));
        when(decoder.decode("support")).thenReturn(token("support", UUID.randomUUID(), "SUPPORT_STAFF"));
        when(decoder.decode("buyer")).thenReturn(token("buyer", UUID.randomUUID(), "BUYER"));
        when(decoder.decode("seller")).thenReturn(token("seller", UUID.randomUUID(), "SELLER"));
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void adminAndSupportReadTheCompleteListInCodeOrderWithSiblingPageMetadata() throws Exception {
        insertWarehouse("WH_Z", "MAINTENANCE");
        insertWarehouse("WH_A", "INACTIVE");
        Map<String, Object> before = warehouseRow(defaultWarehouse);

        for (String actor : List.of("admin", "support")) {
            request(actor, get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.message").value("response success"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].code").value("WH_A"))
                    .andExpect(jsonPath("$.data[1].code").value("WH_MGMT"))
                    .andExpect(jsonPath("$.data[2].code").value("WH_Z"))
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.size").value(3))
                    .andExpect(jsonPath("$.page.totalElements").value(3))
                    .andExpect(jsonPath("$.page.totalPages").value(1));
        }
        assertThat(warehouseRow(defaultWarehouse)).isEqualTo(before);
    }

    @Test
    void listFiltersAllWarehouseStatuses() throws Exception {
        insertWarehouse("WH_INACTIVE", "INACTIVE");
        insertWarehouse("WH_MAINTENANCE", "MAINTENANCE");
        Map<String, String> expected = Map.of("ACTIVE", "WH_MGMT", "INACTIVE", "WH_INACTIVE",
                "MAINTENANCE", "WH_MAINTENANCE");

        for (Map.Entry<String, String> item : expected.entrySet()) {
            request("support", get(PATH).param("status", item.getKey()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].code").value(item.getValue()))
                    .andExpect(jsonPath("$.data[0].status").value(item.getKey()))
                    .andExpect(jsonPath("$.page.totalElements").value(1));
        }
    }

    @Test
    void emptyListAndUnmatchedFilterHaveAnEmptyArrayAndZeroTotals() throws Exception {
        request("admin", get(PATH).param("status", "MAINTENANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));

        jdbc.update("DELETE FROM warehouses");
        request("support", get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void adminAndSupportReadWarehouseDetailsWithoutPageMetadata() throws Exception {
        for (String actor : List.of("admin", "support")) {
            request(actor, get(PATH + "/" + defaultWarehouse))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.message").value("response success"))
                    .andExpect(jsonPath("$.data.id").value(defaultWarehouse.toString()))
                    .andExpect(jsonPath("$.data.code").value("WH_MGMT"))
                    .andExpect(jsonPath("$.data.name").value("Original warehouse"))
                    .andExpect(jsonPath("$.data.address").value("Original address"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.page").doesNotExist());
        }
    }

    @Test
    void unknownWarehouseReturnsTheBusinessNotFoundCodeOnReadAndUpdate() throws Exception {
        UUID unknown = UUID.randomUUID();
        for (MockHttpServletRequestBuilder operation : List.of(get(PATH + "/" + unknown),
                put(PATH + "/" + unknown).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Unknown", "ACTIVE", null)))) {
            request("admin", operation)
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.instance").value(PATH + "/" + unknown))
                    .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouses", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidFilterAndResourceIdsUseTheSharedInvalidParameterCode() throws Exception {
        for (MockHttpServletRequestBuilder operation : List.of(get(PATH).param("status", "BROKEN"),
                get(PATH + "/not-a-uuid"), put(PATH + "/not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("Name", "ACTIVE", null)))) {
            request("admin", operation)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }
    }

    @Test
    void adminUpdatesFieldsAndStatusWhilePreservingCodeAndCreationAudit() throws Exception {
        Map<String, Object> before = warehouseRow(defaultWarehouse);
        update("admin", defaultWarehouse, updateBody("  Updated warehouse  ", "MAINTENANCE", "New address"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.id").value(defaultWarehouse.toString()))
                .andExpect(jsonPath("$.data.code").value("WH_MGMT"))
                .andExpect(jsonPath("$.data.name").value("Updated warehouse"))
                .andExpect(jsonPath("$.data.address").value("New address"))
                .andExpect(jsonPath("$.data.status").value("MAINTENANCE"))
                .andExpect(jsonPath("$.page").doesNotExist());

        Map<String, Object> after = warehouseRow(defaultWarehouse);
        assertThat(after).containsEntry("code", "WH_MGMT").containsEntry("name", "Updated warehouse")
                .containsEntry("address", "New address").containsEntry("status", "MAINTENANCE")
                .containsEntry("created_by", CREATOR_ID).containsEntry("updated_by", ADMIN_ID)
                .containsEntry("created_at", before.get("created_at"));
        assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        assertNoStock();
    }

    @Test
    void updateAcceptsMaximumNameLengthAndClearsOmittedOrNullAddress() throws Exception {
        update("admin", defaultWarehouse, "{\"name\":\"" + "N".repeat(150) + "\",\"status\":\"INACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("N".repeat(150)))
                .andExpect(jsonPath("$.data.address").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        assertThat(warehouseRow(defaultWarehouse)).containsEntry("address", null);

        jdbc.update("UPDATE warehouses SET address = 'Restored' WHERE id = ?", defaultWarehouse);
        update("admin", defaultWarehouse, updateBody("Without address", "ACTIVE", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value(nullValue()));
        assertThat(warehouseRow(defaultWarehouse)).containsEntry("address", null);
    }

    @Test
    void invalidUpdateFieldsUseCommonValidationAndLeaveTheWarehouseUnchanged() throws Exception {
        Map<String, Object> before = warehouseRow(defaultWarehouse);
        update("admin", defaultWarehouse, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "status")));

        String[][] invalid = {
                {" ", "ACTIVE", "name", "VALIDATION_NOT_BLANK"},
                {"N".repeat(151), "ACTIVE", "name", "VALIDATION_SIZE"},
                {"Valid", null, "status", "VALIDATION_NOT_NULL"}
        };
        for (String[] request : invalid) {
            update("admin", defaultWarehouse, updateBody(request[0], request[1], null))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[?(@.field == '" + request[2] + "')].code", hasItem(request[3])));
        }
        assertThat(warehouseRow(defaultWarehouse)).isEqualTo(before);
    }

    @Test
    void invalidEnumAndMalformedUpdateBodiesUseTheSharedInvalidBodyCode() throws Exception {
        Map<String, Object> before = warehouseRow(defaultWarehouse);
        for (String body : List.of(updateBody("Name", "BROKEN", null), updateBody("Name", "active", null),
                "{", "[]", "")) {
            update("admin", defaultWarehouse, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        }
        assertThat(warehouseRow(defaultWarehouse)).isEqualTo(before);
    }

    @Test
    void supportCannotUpdateAndBuyerAndSellerCannotReadOrUpdate() throws Exception {
        Map<String, Object> before = warehouseRow(defaultWarehouse);
        update("support", defaultWarehouse, updateBody("Denied", "MAINTENANCE", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        for (String actor : List.of("buyer", "seller")) {
            for (MockHttpServletRequestBuilder operation : protectedOperations()) {
                request(actor, operation)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
        assertThat(warehouseRow(defaultWarehouse)).isEqualTo(before);
        assertNoStock();
    }

    @Test
    void anonymousAndInvalidTokensCannotReadOrUpdateWarehouses() throws Exception {
        Map<String, Object> before = warehouseRow(defaultWarehouse);
        for (String actor : new String[] {null, "invalid"}) {
            for (MockHttpServletRequestBuilder operation : protectedOperations()) {
                request(actor, operation)
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            }
        }
        assertThat(warehouseRow(defaultWarehouse)).isEqualTo(before);
    }

    @Test
    void aSellerReadsAndSavesTheirOwnWarehouseAndOthersCannotUseTheseRoutes() throws Exception {
        request("seller", get(PATH + "/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_FOUND"));

        String created = request("seller", put(PATH + "/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Kho Quận 7  \",\"address\":\"12 Nguyễn Thị Thập\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Kho Quận 7"))
                .andExpect(jsonPath("$.data.address").value("12 Nguyễn Thị Thập"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.code").value(org.hamcrest.Matchers.startsWith("SELLER-")))
                .andExpect(jsonPath("$.data.sellerId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.data.id");

        request("seller", put(PATH + "/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kho mới\",\"address\":\" \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        assertThat(jdbc.queryForObject("SELECT address FROM warehouses WHERE id = ?::uuid", String.class, id)).isNull();
        request("seller", get(PATH + "/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Kho mới"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouses WHERE seller_id IS NOT NULL", Integer.class))
                .isEqualTo(1);

        request("seller", put(PATH + "/me").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        for (String actor : List.of("admin", "buyer", "support")) {
            request(actor, get(PATH + "/me")).andExpect(status().isForbidden());
            request(actor, put(PATH + "/me").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                    .andExpect(status().isForbidden());
        }
        request("admin", get(PATH))
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].sellerId").isNotEmpty());
    }

    @Test
    void aSellerSetsThePickupAreaAndCheckoutReadsItOnlyWhenComplete() throws Exception {
        UUID sellerId = UUID.randomUUID();
        when(decoder.decode("pickup-seller")).thenReturn(token("pickup-seller", sellerId, "SELLER"));
        String pickup = "/internal/v1/inventory/warehouses/pickup?sellerId=" + sellerId;
        request("buyer", get(pickup)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));

        request("pickup-seller", put(PATH + "/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kho Ba Đình\",\"address\":\" 12 Kim Mã \",\"provinceCode\":\"201\",\"wardCode\":\"20309\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provinceCode").value("201"))
                .andExpect(jsonPath("$.data.wardCode").value("20309"))
                .andExpect(jsonPath("$.data.address").value("12 Kim Mã"));
        UUID warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE seller_id = ?", UUID.class, sellerId);
        request("buyer", get(pickup)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouseId").value(warehouseId.toString()))
                .andExpect(jsonPath("$.data.sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.data.provinceCode").value("201"))
                .andExpect(jsonPath("$.data.wardCode").value("20309"))
                .andExpect(jsonPath("$.data.line1").value("12 Kim Mã"));
        request("pickup-seller", get(PATH + "/me")).andExpect(jsonPath("$.data.wardCode").value("20309"));

        UUID ownSku = UUID.randomUUID();
        UUID sharedSku = UUID.randomUUID();
        UUID shared = jdbc.queryForObject("SELECT id FROM warehouses WHERE seller_id IS NULL ORDER BY code LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, 6, 6), (?, ?, 4, 4)
                """, ownSku, warehouseId, sharedSku, shared);
        request("buyer", get(pickup + "&skuIds=" + ownSku)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouseId").value(warehouseId.toString()));
        request("buyer", get(pickup + "&skuIds=" + ownSku + "&skuIds=" + sharedSku)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouseId").value(warehouseId.toString()));
        UUID otherSellerSku = UUID.randomUUID();
        UUID otherSeller = jdbc.queryForObject("""
                INSERT INTO warehouses (code, name, seller_id) VALUES (?, 'Other seller', ?) RETURNING id
                """, UUID.class, "S-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, 2, 2)
                """, otherSellerSku, otherSeller);
        for (String skus : List.of("&skuIds=" + sharedSku, "&skuIds=" + ownSku + "&skuIds=" + otherSellerSku,
                "&skuIds=" + UUID.randomUUID())) {
            request("buyer", get(pickup + skus)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));
        }

        for (String body : List.of(
                "{\"name\":\"Kho\",\"address\":\"12 Kim Mã\",\"provinceCode\":\"201\"}",
                "{\"name\":\"Kho\",\"address\":\"12 Kim Mã\",\"wardCode\":\"20309\"}",
                "{\"name\":\"Kho\",\"address\":\" \",\"provinceCode\":\"201\",\"wardCode\":\"20309\"}",
                "{\"name\":\"Kho\",\"address\":\"" + "x".repeat(256) + "\",\"provinceCode\":\"201\",\"wardCode\":\"20309\"}",
                "{\"name\":\"Kho\",\"address\":\"12 Kim Mã\",\"provinceCode\":\"0\",\"wardCode\":\"20309\"}",
                "{\"name\":\"Kho\",\"address\":\"12 Kim Mã\",\"provinceCode\":\"201\",\"wardCode\":\"20A\"}")) {
            request("pickup-seller", put(PATH + "/me").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        request("buyer", get(pickup)).andExpect(jsonPath("$.data.line1").value("12 Kim Mã"));

        jdbc.update("UPDATE warehouses SET status = 'MAINTENANCE' WHERE id = ?", warehouseId);
        request("buyer", get(pickup)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));
        jdbc.update("UPDATE warehouses SET status = 'ACTIVE' WHERE id = ?", warehouseId);

        request("pickup-seller", put(PATH + "/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kho Ba Đình\",\"address\":\"12 Kim Mã\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.provinceCode").value(nullValue()));
        request("buyer", get(pickup)).andExpect(jsonPath("$.data").value(nullValue()));

        request("admin", get(pickup)).andExpect(status().isForbidden());
        mvc.perform(get(pickup)).andExpect(status().isUnauthorized());
        request("buyer", get("/internal/v1/inventory/warehouses/pickup?sellerId=nope")).andExpect(status().isBadRequest());
    }

    @Test
    void maintenanceAndInactiveBlockExplicitAndDefaultIntakeWithoutChangingBalancesOrLedger() throws Exception {
        UUID sku = UUID.randomUUID();
        intake(sku, null, 20).andExpect(status().isCreated());
        Map<String, Object> balanceBefore = balanceRow(sku);
        List<Map<String, Object>> ledgerBefore = ledgerRows(sku);

        for (String warehouseStatus : List.of("MAINTENANCE", "INACTIVE")) {
            update("admin", defaultWarehouse, updateBody("Unavailable", warehouseStatus, null))
                    .andExpect(status().isOk());
            for (UUID warehouse : new UUID[] {null, defaultWarehouse}) {
                intake(sku, warehouse, 5)
                        .andExpect(status().isConflict())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("urn:problem:domain-error"))
                        .andExpect(jsonPath("$.status").value(409))
                        .andExpect(jsonPath("$.instance").value(INTAKE_PATH))
                        .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_UNAVAILABLE"))
                        .andExpect(jsonPath("$.detail").value("Warehouse is not active for stock intake"));
            }
            assertThat(balanceRow(sku)).isEqualTo(balanceBefore);
            assertThat(ledgerRows(sku)).isEqualTo(ledgerBefore);
        }

        update("admin", defaultWarehouse, updateBody("Available again", "ACTIVE", null))
                .andExpect(status().isOk());
        intake(sku, null, 5).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total").value(25));
        assertThat(ledgerRows(sku)).hasSize(2);
    }

    @Test
    void blockedIntakeDoesNotCreateBalanceOrMovementRowsForANewSku() throws Exception {
        UUID maintenanceWarehouse = insertWarehouse("WH_OFFLINE", "MAINTENANCE");
        UUID sku = UUID.randomUUID();
        intake(sku, maintenanceWarehouse, 7)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_UNAVAILABLE"));
        assertNoStock();

        intake(sku, null, 7).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warehouseId").value(defaultWarehouse.toString()));
    }

    @Test
    void unknownExplicitAndMissingConfiguredDefaultKeepTheirExistingErrorCodes() throws Exception {
        UUID sku = UUID.randomUUID();
        intake(sku, UUID.randomUUID(), 7)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_FOUND"));
        jdbc.update("DELETE FROM warehouses WHERE id = ?", defaultWarehouse);
        intake(sku, null, 7)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INVENTORY_DEFAULT_WAREHOUSE_NOT_FOUND"));
        assertNoStock();
    }

    @Test
    void intakeWaitsForMaintenanceUpdateAndRejectsTheCommittedStatus() throws Exception {
        UUID sku = UUID.randomUUID();
        CountDownLatch maintenanceApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> maintenance = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(transaction -> {
                        try {
                            update("admin", defaultWarehouse, updateBody("Maintenance", "MAINTENANCE", null))
                                    .andExpect(status().isOk());
                            maintenanceApplied.countDown();
                            assertThat(allowCommit.await(15, TimeUnit.SECONDS)).isTrue();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }));
            assertThat(maintenanceApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> intakeResult = pool.submit(() -> intake(sku, defaultWarehouse, 7)
                    .andReturn().getResponse().getStatus());

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                      AND query ILIKE '%warehouses%' AND pid <> pg_backend_pid()
                    """, Integer.class)).isPositive());
            assertThat(intakeResult.isDone()).isFalse();
            allowCommit.countDown();
            maintenance.get(15, TimeUnit.SECONDS);
            assertThat(intakeResult.get(15, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(warehouseRow(defaultWarehouse)).containsEntry("status", "MAINTENANCE");
            assertNoStock();
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

    private ResultActions request(String actor, MockHttpServletRequestBuilder request) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + actor);
        return mvc.perform(request);
    }

    private ResultActions update(String actor, UUID warehouse, String body) throws Exception {
        return request(actor, put(PATH + "/" + warehouse).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<MockHttpServletRequestBuilder> protectedOperations() {
        return List.of(get(PATH), get(PATH + "/" + defaultWarehouse), put(PATH + "/" + defaultWarehouse)
                .contentType(MediaType.APPLICATION_JSON).content(updateBody("Denied", "MAINTENANCE", null)));
    }

    private ResultActions intake(UUID sku, UUID warehouse, int quantity) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("skuId", sku);
        values.put("quantity", quantity);
        if (warehouse != null) values.put("warehouseId", warehouse);
        return request("admin", post(INTAKE_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(values)));
    }

    private static String updateBody(String name, String status, String address) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("status", status);
        values.put("address", address);
        return JSON.writeValueAsString(values);
    }

    private UUID insertWarehouse(String code, String status) {
        return jdbc.queryForObject("""
                INSERT INTO warehouses (code, name, address, status, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'Original warehouse', 'Original address', CAST(? AS warehouse_status),
                        '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z', ?, ?) RETURNING id
                """, UUID.class, code, status, CREATOR_ID, CREATOR_ID);
    }

    private Map<String, Object> warehouseRow(UUID warehouse) {
        return jdbc.queryForMap("""
                SELECT id, code, name, address, status::text AS status, created_at, updated_at, created_by, updated_by
                FROM warehouses WHERE id = ?
                """, warehouse);
    }

    private Map<String, Object> balanceRow(UUID sku) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ?", sku);
    }

    private List<Map<String, Object>> ledgerRows(UUID sku) {
        return jdbc.queryForList("SELECT * FROM inventory_movements WHERE sku_id = ? ORDER BY created_at, id", sku);
    }

    private void assertNoStock() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_balances", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class)).isZero();
    }

    private static Jwt token(String value, UUID userId, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
