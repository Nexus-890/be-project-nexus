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
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/warehouse-create-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_001",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class WarehouseCreateIntegrationTest {
    private static final String PATH = "/api/v1/warehouses";
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    static { POSTGRES.start(); }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;

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
        reset(decoder);
        when(decoder.decode("admin")).thenReturn(token("admin", ADMIN_ID, "ADMIN"));
        for (String role : List.of("SUPPORT_STAFF", "BUYER", "SELLER")) {
            when(decoder.decode(role)).thenReturn(token(role, UUID.randomUUID(), role));
        }
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void adminCreatesAnActiveWarehouseWithAuditMetadataAndNoStock() throws Exception {
        String response = create("admin", body("WH_ADMIN", "Main warehouse", "123 Main Street"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.code").value("WH_ADMIN"))
                .andExpect(jsonPath("$.data.name").value("Main warehouse"))
                .andExpect(jsonPath("$.data.address").value("123 Main Street"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.page").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JSON.readTree(response).path("data").path("id").asString());
        assertThat(id.version()).isEqualTo(7);
        assertThat(row("WH_ADMIN"))
                .containsEntry("id", id).containsEntry("name", "Main warehouse")
                .containsEntry("address", "123 Main Street").containsEntry("status", "ACTIVE")
                .containsEntry("created_by", ADMIN_ID).containsEntry("updated_by", ADMIN_ID)
                .containsKeys("created_at", "updated_at")
                .doesNotContainValue(null);
        assertThat(warehouseCount()).isEqualTo(1);
        assertNoStock();
    }

    @Test
    void addressMayBeOmittedOrExplicitlyNull() throws Exception {
        create("admin", "{\"code\":\"WH_OMITTED\",\"name\":\"Without address\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.address").value(nullValue()));
        create("admin", body("WH_NULL", "Null address", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.address").value(nullValue()));

        assertThat(row("WH_OMITTED")).containsEntry("address", null);
        assertThat(row("WH_NULL")).containsEntry("address", null);
        assertThat(warehouseCount()).isEqualTo(2);
    }

    @Test
    void surroundingWhitespaceIsRemovedFromCodeAndName() throws Exception {
        create("admin", body("  WH_TRIM  ", "  Trimmed warehouse  ", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("WH_TRIM"))
                .andExpect(jsonPath("$.data.name").value("Trimmed warehouse"));

        assertThat(row("WH_TRIM")).containsEntry("name", "Trimmed warehouse");
        assertThat(warehouseCount()).isEqualTo(1);
    }

    @Test
    void acceptsMaximumLengthsAndKeepsCodesCaseSensitive() throws Exception {
        String code = "W".repeat(40);
        String name = "N".repeat(150);
        String address = "A".repeat(400);
        create("admin", body(code, name, address))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.address").value(address));
        create("admin", body(code.toLowerCase(), "Lowercase code", null))
                .andExpect(status().isCreated());

        assertThat(warehouseCount()).isEqualTo(2);
    }

    @Test
    void duplicateNormalizedCodeReturnsConflictWithoutChangingTheOriginalWarehouse() throws Exception {
        create("admin", body("WH_DUPLICATE", "Original", "Original address"))
                .andExpect(status().isCreated());
        Map<String, Object> original = row("WH_DUPLICATE");

        create("admin", body("  WH_DUPLICATE  ", "Replacement", "Different address"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:domain-error"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Warehouse code already exists"))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.code").value("INVENTORY_DUPLICATE_WAREHOUSE_CODE"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(row("WH_DUPLICATE")).isEqualTo(original);
        assertThat(warehouseCount()).isEqualTo(1);
        assertNoStock();
    }

    @Test
    void simultaneousDuplicateCreatesProduceOneWarehouseAndConflictsForTheRest() throws Exception {
        int callers = 6;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = IntStream.range(0, callers)
                    .mapToObj(index -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        var response = create("admin", body("WH_RACE", "Concurrent " + index, null))
                                .andReturn().getResponse();
                        if (response.getStatus() == 409) {
                            assertThat(JSON.readTree(response.getContentAsString()).path("code").asString())
                                    .isEqualTo("INVENTORY_DUPLICATE_WAREHOUSE_CODE");
                        }
                        return response.getStatus();
                    })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(callers - 1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        assertThat(warehouseCount()).isEqualTo(1);
        assertThat(row("WH_RACE")).containsEntry("status", "ACTIVE").containsEntry("created_by", ADMIN_ID);
        assertNoStock();
    }

    @Test
    void missingAndNullCodeAndNameReportBothFieldErrors() throws Exception {
        for (String request : List.of("{}", body(null, null, null))) {
            create("admin", request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("code", "name")))
                    .andExpect(jsonPath("$.errors[*].code", containsInAnyOrder("VALIDATION_NOT_BLANK", "VALIDATION_NOT_BLANK")));
        }
        assertThat(warehouseCount()).isZero();
    }

    @Test
    void blankAndOverlongFieldsUseSharedValidationErrorsWithoutInsertingRows() throws Exception {
        String[][] requests = {
                {" ", "Valid name", "code", "VALIDATION_NOT_BLANK"},
                {"VALID", " ", "name", "VALIDATION_NOT_BLANK"},
                {"W".repeat(41), "Valid name", "code", "VALIDATION_SIZE"},
                {"VALID", "N".repeat(151), "name", "VALIDATION_SIZE"}
        };
        for (String[] request : requests) {
            create("admin", body(request[0], request[1], null))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.instance").value(PATH))
                    .andExpect(jsonPath("$.errors[?(@.field == '" + request[2] + "')].code", hasItem(request[3])));
        }
        assertThat(warehouseCount()).isZero();
    }

    @Test
    void malformedOrMissingJsonUsesTheSharedInvalidBodyCode() throws Exception {
        for (String request : List.of("{", "[]", "")) {
            create("admin", request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        }
        assertThat(warehouseCount()).isZero();
    }

    @Test
    void supportBuyerAndSellerCannotCreateWarehouses() throws Exception {
        for (String role : List.of("SUPPORT_STAFF", "BUYER", "SELLER")) {
            create(role, body("WH_DENIED", "Forbidden warehouse", null))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.instance").value(PATH))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(warehouseCount()).isZero();
        assertNoStock();
    }

    @Test
    void anonymousAndInvalidTokenRequestsReturnTheSharedUnauthorizedProblem() throws Exception {
        for (String token : new String[] {null, "invalid"}) {
            create(token, body("WH_UNAUTHORIZED", "Unauthorized warehouse", null))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.instance").value(PATH))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertThat(warehouseCount()).isZero();
        assertNoStock();
    }

    private ResultActions create(String token, String body) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request);
    }

    private static String body(String code, String name, String address) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", code);
        values.put("name", name);
        values.put("address", address);
        return JSON.writeValueAsString(values);
    }

    private Map<String, Object> row(String code) {
        return jdbc.queryForMap("""
                SELECT id, code, name, address, status::text AS status, created_at, updated_at, created_by, updated_by
                FROM warehouses WHERE code = ?
                """, code);
    }

    private int warehouseCount() {
        return jdbc.queryForObject("SELECT count(*) FROM warehouses", Integer.class);
    }

    private void assertNoStock() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_balances", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isZero();
    }

    private static Jwt token(String value, UUID userId, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
