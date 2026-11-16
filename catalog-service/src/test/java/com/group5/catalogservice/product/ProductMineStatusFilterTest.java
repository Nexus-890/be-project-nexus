package com.group5.catalogservice.product;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/catalog-mine-status-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.outbox.relay.enabled=false", "nexus.catalog.order-references.mode=stub",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false",
        "nexus.storage.supabase-url=http://127.0.0.1:1", "nexus.storage.service-key=fixture-key",
        "nexus.storage.bucket=product-images"
})
class ProductMineStatusFilterTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.catalogservice.PostgresTestDatabase("postgres:18");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String CATEGORY = "69000000-0000-4000-8000-000000000102";
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID OTHER_SELLER = UUID.randomUUID();
    static { POSTGRES.start(); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private final List<UUID> drafts = new ArrayList<>();
    private UUID active;
    private UUID inactive;
    private UUID deletedDraft;
    private UUID otherDraft;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        drafts.clear();
        for (int index = 0; index < 3; index++) {
            drafts.add(product(SELLER, "DRAFT", false));
        }
        active = product(SELLER, "ACTIVE", false);
        inactive = product(SELLER, "INACTIVE", false);
        deletedDraft = product(SELLER, "DRAFT", true);
        otherDraft = product(OTHER_SELLER, "DRAFT", false);
        when(decoder.decode("seller")).thenReturn(Jwt.withTokenValue("seller").header("alg", "RS256")
                .subject(SELLER.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("SELLER")).claim("privileges", List.of("PRODUCT.LIST")).build());
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM products WHERE seller_id IN (?, ?)", SELLER, OTHER_SELLER);
    }

    @Test
    void withoutStatusTheSellerSeesEveryOwnProductThatIsNotDeleted() throws Exception {
        JsonNode body = read(get("/api/v1/products/mine").param("size", "100"));

        List<UUID> expected = new ArrayList<>(drafts);
        expected.add(active);
        expected.add(inactive);
        assertThat(ids(body)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(body.path("page").path("totalElements").asInt()).isEqualTo(5);
    }

    @Test
    void draftStatusReturnsOnlyDraftsWithPageTotalsForTheFilter() throws Exception {
        JsonNode first = read(get("/api/v1/products/mine").param("status", "DRAFT").param("page", "0").param("size", "2"));
        JsonNode second = read(get("/api/v1/products/mine").param("status", "DRAFT").param("page", "1").param("size", "2"));

        assertThat(first.path("page").path("totalElements").asInt()).isEqualTo(3);
        assertThat(first.path("page").path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("data").size()).isEqualTo(2);
        assertThat(second.path("data").size()).isEqualTo(1);
        assertThat(statuses(first)).containsOnly("DRAFT");
        assertThat(statuses(second)).containsOnly("DRAFT");
        List<UUID> seen = new ArrayList<>(ids(first));
        seen.addAll(ids(second));
        assertThat(seen).containsExactlyInAnyOrderElementsOf(drafts);
    }

    @Test
    void anotherSellersProductsNeverAppearUnderAnyStatus() throws Exception {
        for (String value : List.of("DRAFT", "ACTIVE", "INACTIVE")) {
            JsonNode body = read(get("/api/v1/products/mine").param("status", value).param("size", "100"));
            assertThat(ids(body)).doesNotContain(otherDraft, deletedDraft);
            assertThat(statuses(body)).containsOnly(value);
        }
        assertThat(ids(read(get("/api/v1/products/mine").param("status", "ACTIVE")))).containsExactly(active);
        assertThat(ids(read(get("/api/v1/products/mine").param("status", "INACTIVE")))).containsExactly(inactive);
    }

    @Test
    void anUnknownStatusIsTheServiceInvalidParameterProblem() throws Exception {
        mvc.perform(get("/api/v1/products/mine").param("status", "SOLD").header("Authorization", "Bearer seller"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    private UUID product(UUID seller, String status, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, category_id, seller_id, name, slug, status, deleted_at)
                VALUES (?, CAST(? AS uuid), ?, 'Mine status fixture', ?, CAST(? AS product_status), ?)
                """, id, CATEGORY, seller, "mine-status-test-" + id, status,
                deleted ? Timestamp.from(Instant.now()) : null);
        return id;
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request.header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<UUID> ids(JsonNode body) {
        List<UUID> ids = new ArrayList<>();
        body.path("data").forEach(product -> ids.add(UUID.fromString(product.path("id").asString())));
        return ids;
    }

    private static List<String> statuses(JsonNode body) {
        List<String> statuses = new ArrayList<>();
        body.path("data").forEach(product -> statuses.add(product.path("status").asString()));
        return statuses;
    }
}
