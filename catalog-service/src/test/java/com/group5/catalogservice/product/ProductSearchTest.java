package com.group5.catalogservice.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.group5.catalogservice.PostgresTestDatabase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/catalog-search-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.outbox.relay.enabled=false", "nexus.catalog.order-references.mode=stub",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false",
        "nexus.security.public-endpoints[0].method=GET", "nexus.security.public-endpoints[0].path=/api/v1/products",
        "nexus.storage.supabase-url=http://127.0.0.1:1", "nexus.storage.service-key=fixture-key",
        "nexus.storage.bucket=product-images"
})
class ProductSearchTest {
    private static final PostgreSQLContainer POSTGRES = new PostgresTestDatabase("postgres:18");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String CATEGORY = "69000000-0000-4000-8000-000000000102";
    private static final String OTHER_CATEGORY = "69000000-0000-4000-8000-000000000103";
    private static final UUID SELLER = UUID.randomUUID();
    static { POSTGRES.start(); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @MockitoBean private JwtDecoder decoder;
    private final List<UUID> fixtures = new ArrayList<>();
    private MockMvc mvc;
    private String keyword;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        keyword = "search-" + UUID.randomUUID();
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
        for (UUID id : fixtures) {
            jdbc.update("DELETE FROM skus WHERE product_id = ?", id);
            jdbc.update("DELETE FROM products WHERE id = ?", id);
        }
    }

    @Test
    void titleSearchIsCaseInsensitiveAndLiteralAndDoesNotExposeDraftOrDeletedProducts() throws Exception {
        UUID match = product("Wireless " + keyword + " headphones", CATEGORY, false, 200, 1);
        product("Unrelated", CATEGORY, false, 100, 1);
        UUID draft = product(keyword + " draft", CATEGORY, false, 100, 1);
        jdbc.update("UPDATE products SET status='DRAFT' WHERE id=?", draft);
        UUID deleted = product(keyword + " deleted", CATEGORY, false, 100, 1);
        jdbc.update("UPDATE products SET deleted_at=now() WHERE id=?", deleted);
        JsonNode result = read(get("/api/v1/products").param("q", "  " + keyword.toUpperCase() + "  "));
        assertThat(ids(result)).containsExactly(match.toString());
        assertThat(result.path("page").path("totalElements").asLong()).isEqualTo(1);

        UUID literal = product(keyword + " 50%_off\\sale", CATEGORY, false, 200, 1);
        product(keyword + " 50XXoffXsale", CATEGORY, false, 200, 1);
        assertThat(ids(read(get("/api/v1/products").param("q", "50%_off\\sale")))).containsExactly(literal.toString());
    }

    @Test
    void allFiltersAndPriceOrderingApplyBeforePagingWithAccurateDistinctCounts() throws Exception {
        UUID low = product(keyword + " low", CATEGORY, true, 100, 1);
        sku(low, 900);
        UUID high = product(keyword + " high", CATEGORY, true, 200, 1);
        product(keyword + " too expensive", CATEGORY, true, 400, 1);
        product(keyword + " buy now", CATEGORY, false, 50, 1);
        product(keyword + " other category", OTHER_CATEGORY, true, 50, 1);
        product(keyword + " no price", CATEGORY, true, null, 1);
        JsonNode first = read(search().param("categoryId", CATEGORY).param("listingType", "auction")
                .param("maxPrice", "250").param("sort", "price,desc").param("size", "1"));
        assertThat(ids(first)).containsExactly(high.toString());
        assertThat(first.path("page").path("totalElements").asLong()).isEqualTo(2);
        assertThat(first.path("page").path("totalPages").asInt()).isEqualTo(2);
        JsonNode second = read(search().param("categoryId", CATEGORY).param("listingType", "auction")
                .param("maxPrice", "250").param("sort", "price,desc").param("size", "1").param("page", "1"));
        assertThat(ids(second)).containsExactly(low.toString());
        assertThat(second.path("data").get(0).path("maxPrice").decimalValue()).isEqualByComparingTo("900");
        assertThat(ids(read(search().param("categoryId", CATEGORY).param("listingType", "buy-now")))).hasSize(1);
    }

    @Test
    void relevanceNewestAndPriceOrdersAreStableAndNullPricesComeLast() throws Exception {
        UUID exact = product(keyword, CATEGORY, false, 300, 1);
        UUID prefix = product(keyword + " prefix", CATEGORY, false, 100, 2);
        UUID substring = product("A " + keyword, CATEGORY, false, 100, 3);
        UUID noPrice = product(keyword + " no price", CATEGORY, false, null, 4);
        assertThat(ids(read(search()))).containsExactly(exact.toString(), noPrice.toString(), prefix.toString(), substring.toString());
        assertThat(ids(read(search().param("sort", "createdAt,desc")))).containsExactly(noPrice.toString(), substring.toString(), prefix.toString(), exact.toString());
        List<String> tied = List.of(prefix.toString(), substring.toString()).stream().sorted().toList();
        var expectedAsc = new ArrayList<>(tied);
        expectedAsc.add(exact.toString()); expectedAsc.add(noPrice.toString());
        assertThat(ids(read(search().param("sort", "price,asc")))).containsExactlyElementsOf(expectedAsc);
        var expectedDesc = new ArrayList<>(List.of(exact.toString()));
        expectedDesc.addAll(tied); expectedDesc.add(noPrice.toString());
        assertThat(ids(read(search().param("sort", "price,desc")))).containsExactlyElementsOf(expectedDesc);
        assertThat(ids(read(search().param("sort", "price,asc").param("size", "1").param("page", "1"))))
                .containsExactly(tied.get(1));
    }

    @Test
    void unmatchedTitleReturnsSuccessfulEmptyPageAndInvalidParametersReturnProblemDetails() throws Exception {
        product(keyword, CATEGORY, false, 100, 1);
        JsonNode empty = read(get("/api/v1/products").param("q", keyword + "-does-not-exist"));
        assertThat(ids(empty)).isEmpty();
        assertThat(empty.path("page").path("totalElements").asLong()).isZero();
        assertThat(empty.path("page").path("totalPages").asInt()).isZero();
        for (String[] invalid : List.of(new String[]{"sort", "price,drop"}, new String[]{"listingType", "invalid"},
                new String[]{"maxPrice", "-1"}, new String[]{"maxPrice", "0.001"},
                new String[]{"q", "a".repeat(256)}, new String[]{"page", "-1"},
                new String[]{"size", "0"})) {
            var response = mvc.perform(get("/api/v1/products").param(invalid[0], invalid[1])).andExpect(status().isBadRequest())
                    .andReturn().getResponse();
            assertThat(JSON.readTree(response.getContentAsString()).path("code").asString()).isEqualTo("CATALOG_DATA_INVALID");
        }
    }

    private MockHttpServletRequestBuilder search() {
        return get("/api/v1/products").param("q", keyword);
    }

    private UUID product(String title, String category, boolean auction, Integer price, int day) {
        UUID id = UUID.randomUUID(); fixtures.add(id);
        jdbc.update("""
                INSERT INTO products (id, category_id, seller_id, name, slug, status, auction_enable, created_at)
                VALUES (?, CAST(? AS uuid), ?, ?, ?, 'ACTIVE', ?, CAST(? AS timestamptz))
                """, id, category, SELLER, title, "search-test-" + id, auction, "2026-11-" + String.format("%02d", day) + "T00:00:00Z");
        if (price != null) sku(id, price);
        return id;
    }

    private void sku(UUID product, int price) {
        jdbc.update("INSERT INTO skus (product_id, sku_code, color, size, price) VALUES (?, ?, 'Black', ?, ?)",
                product, "SEARCH-" + UUID.randomUUID(), UUID.randomUUID().toString(), price);
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> ids(JsonNode response) {
        List<String> ids = new ArrayList<>();
        response.path("data").forEach(product -> ids.add(product.path("id").asString()));
        return ids;
    }
}
