package com.group5.catalogservice.product;

import com.group5.catalogservice.product.event.ProductFlagsConsumer;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.common_events.EventEnvelope;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/sprint3-catalog-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/catalog-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.admin.auto-create=false", "spring.kafka.listener.auto-startup=false",
        "nexus.catalog.order-references.mode=stub", "nexus.outbox.relay.enabled=false"
})
class Sprint3CatalogIntegrationTest {
    private static final String PRODUCTS = "/api/v1/products";
    private static final String ADMIN_URL = System.getenv("TEST_POSTGRES_ADMIN_URL");
    private static final String USER = System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "");
    private static final String DATABASE = "catalog_sprint3_" + UUID.randomUUID().toString().replace("-", "");
    private static PostgreSQLContainer postgres;

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired ProductFlagsConsumer flags;
    @Autowired WebApplicationContext context;
    @MockitoBean JwtDecoder decoder;
    private MockMvc mvc;
    private final UUID owner = UUID.randomUUID();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws SQLException {
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

    @BeforeEach
    void setup() {
        when(decoder.decode("owner")).thenReturn(token("owner", owner, "SELLER"));
        when(decoder.decode("other")).thenReturn(token("other", UUID.randomUUID(), "SELLER"));
        when(decoder.decode("buyer")).thenReturn(token("buyer", UUID.randomUUID(), "BUYER"));
        mvc = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void combinesPhraseCategoryActiveSkuPriceSortAndPagination() throws Exception {
        UUID category = category("Shirts", null, 1);
        UUID cheap = product(category, "Áo thun cotton", null, "ACTIVE");
        UUID expensive = product(category, "Cotton", "ÁO THUN cổ tròn", "ACTIVE");
        UUID hidden = product(category, "Áo thun ẩn", null, "INACTIVE");
        UUID draft = product(category, "Áo thun nháp", null, "DRAFT");
        UUID nonPhrase = product(category, "Áo cotton thun", null, "ACTIVE");
        UUID split = product(category, "Áo", "thun", "ACTIVE");
        UUID softDeleted = product(category, "Áo thun đã xóa", null, "ACTIVE");
        UUID wrongCategory = product(category("Other shirts", null, 1), "Áo thun khác", null, "ACTIVE");
        for (UUID id : List.of(hidden, draft, nonPhrase, split, softDeleted, wrongCategory)) {
            sku(id, 150, "ACTIVE");
        }
        jdbc.update("UPDATE products SET deleted_at = now() WHERE id = ?", softDeleted);
        sku(cheap, 125, "ACTIVE");
        sku(cheap, 85, "INACTIVE");
        sku(expensive, 240, "ACTIVE");
        jdbc.update("UPDATE products SET created_at = now() - interval '1 day' WHERE id = ?", cheap);

        for (String sort : List.of("relevance", "price_asc", "price_desc", "newest")) {
            UUID first = List.of("relevance", "price_asc").contains(sort) ? cheap : expensive;
            mvc.perform(get(PRODUCTS + "/search").param("q", "ao thun").param("categoryId", category.toString())
                            .param("minPrice", "100").param("maxPrice", "250").param("sort", sort).param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2))
                    .andExpect(jsonPath("$.page.totalPages").value(2)).andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(first.toString()));
        }
        mvc.perform(get(PRODUCTS + "/search").param("q", "ÁO THUN").param("categoryId", category.toString())
                        .param("minPrice", "100").param("maxPrice", "200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(cheap.toString()))
                .andExpect(jsonPath("$.data[0].minPrice").value(125));
        mvc.perform(get(PRODUCTS + "/search").param("minPrice", "251").param("maxPrice", "250"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATALOG_DATA_INVALID"));
    }

    @Test
    void selectivePhrasePlanUsesFullTextIndexes() {
        UUID category = category("Search plan", null, 1);
        jdbc.update("""
                INSERT INTO products (category_id, seller_id, name, slug, status)
                SELECT ?, ?, 'Unrelated catalog entry', 'fts-plan-' || item, 'ACTIVE'
                FROM generate_series(1, 5000) item
                """, category, owner);
        product(category, "Bình giữ nhiệt QuasarXYZ", null, "ACTIVE");
        jdbc.execute("VACUUM (ANALYZE) products");
        var parameters = new MapSqlParameterSource("q", "nhiet quasarxyz")
                .addValue("categoryId", null).addValue("minPrice", null).addValue("maxPrice", null)
                .addValue("sort", "relevance");
        List<String> plan = namedJdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) "
                + ProductRepository.SEARCH_QUERY + " LIMIT 10", parameters, String.class);
        assertThat(plan).anySatisfy(line -> assertThat(line).contains("Bitmap Index Scan on idx_products_name_search"));
        assertThat(plan).anySatisfy(line -> assertThat(line).contains("Bitmap Index Scan on idx_products_description_search"));
    }

    @Test
    void listsFlatCategoriesByNameParentAndLevel() throws Exception {
        UUID parent = category("Fabric", null, 1);
        UUID child = category("Cotton alpha", parent, 2);
        category("Cotton beta", parent, 2);
        category("Linen", parent, 2);
        category("Cotton root", null, 1);
        category("Cotton grandchild", child, 3);
        UUID archived = category("Cotton deleted", parent, 2);
        jdbc.update("UPDATE categories SET deleted_at = now() WHERE id = ?", archived);
        mvc.perform(get("/api/v1/categories").param("q", "COTTON").param("parentId", parent.toString())
                        .param("level", "2").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(child.toString()))
                .andExpect(jsonPath("$.data[0].parentId").value(parent.toString()))
                .andExpect(jsonPath("$.data[0].level").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void ownerSeesDraftAndInactiveWhileOtherCallersAreRejected() throws Exception {
        UUID category = category("Seller products", null, 1);
        UUID draft = product(category, "Seller draft", "Edit this", "DRAFT");
        UUID inactive = product(category, "Seller inactive", null, "INACTIVE");
        jdbc.update("UPDATE products SET auction_enable = true WHERE id = ?", draft);
        String ownPage = mvc.perform(get(PRODUCTS + "/mine").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = JsonMapper.shared().readTree(ownPage).required("data");
        assertThat(items).anySatisfy(item -> {
            assertThat(item.required("id").asString()).isEqualTo(draft.toString());
            assertThat(item.required("description").asString()).isEqualTo("Edit this");
            assertThat(item.required("auctionEnable").asBoolean()).isTrue();
        });
        assertThat(items).anySatisfy(item -> {
            assertThat(item.required("id").asString()).isEqualTo(inactive.toString());
            assertThat(item.required("description").isNull()).isTrue();
            assertThat(item.required("auctionEnable").asBoolean()).isFalse();
        });
        mvc.perform(get(PRODUCTS + "/mine/{id}", draft).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        mvc.perform(get(PRODUCTS + "/mine/{id}", inactive).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INACTIVE"));
        mvc.perform(get(PRODUCTS + "/mine/{id}", draft).header("Authorization", "Bearer other"))
                .andExpect(status().isNotFound());
        mvc.perform(get(PRODUCTS + "/mine/{id}", draft).header("Authorization", "Bearer buyer"))
                .andExpect(status().isForbidden());
        mvc.perform(get(PRODUCTS + "/{id}", draft)).andExpect(status().isNotFound());
    }

    @Test
    void orderFlagsAllProductsAndProtectsDeleteWhileAuctionFlagsTrackOpenAuctions() throws Exception {
        UUID category = category("Flagged", null, 1);
        UUID first = product(category, "Flagged first", null, "ACTIVE");
        UUID second = product(category, "Flagged second", null, "ACTIVE");
        var order = envelope("OrderCreated", UUID.randomUUID(), Map.of("productIds", List.of(first, second)));
        flags.receive(order);
        flags.receive(order);
        assertThat(flag(first, "has_order")).isTrue();
        assertThat(flag(second, "has_order")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                order.eventId())).isEqualTo(1);
        mvc.perform(delete(PRODUCTS + "/{id}", first).header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_HAS_ORDERS"));

        UUID auction1 = UUID.randomUUID();
        UUID auction2 = UUID.randomUUID();
        var created = auctionEvent("AuctionCreated", auction1, first);
        flags.receive(created);
        flags.receive(created);
        assertThat(flag(first, "in_auction")).isTrue();
        flags.receive(auctionEvent("AuctionCreated", auction2, first));
        var ended = auctionEvent("AuctionEnded", auction1, first);
        flags.receive(ended);
        flags.receive(ended);
        assertThat(flag(first, "in_auction")).isTrue();
        flags.receive(auctionEvent("AuctionCancelled", auction2, first));
        assertThat(flag(first, "in_auction")).isFalse();
        UUID auction3 = UUID.randomUUID();
        flags.receive(auctionEvent("AuctionEnded", auction3, first));
        flags.receive(auctionEvent("AuctionCreated", auction3, first));
        assertThat(flag(first, "in_auction")).isFalse();
    }

    private UUID category(String name, UUID parent, int level) {
        return jdbc.queryForObject("""
                INSERT INTO categories (parent_id, name, slug, level) VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, parent, name, UUID.randomUUID().toString(), level);
    }

    private UUID product(UUID category, String name, String description, String status) {
        return jdbc.queryForObject("""
                INSERT INTO products (category_id, seller_id, name, slug, description, status)
                VALUES (?, ?, ?, ?, ?, ?::product_status) RETURNING id
                """, UUID.class, category, owner, name, UUID.randomUUID().toString(), description, status);
    }

    private void sku(UUID product, int price, String status) {
        jdbc.update("INSERT INTO skus (product_id, sku_code, color, price, status) VALUES (?, ?, ?, ?, ?::sku_status)",
                product, UUID.randomUUID().toString(), status, price, status);
    }

    private boolean flag(UUID product, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM products WHERE id = ?", Boolean.class, product);
    }

    private static EventEnvelope<JsonNode> auctionEvent(String type, UUID auction, UUID product) {
        return envelope(type, auction, Map.of("auctionId", auction, "productId", product));
    }

    private static EventEnvelope<JsonNode> envelope(String type, UUID aggregate, Map<String, Object> fields) {
        JsonNode payload = JsonMapper.shared().valueToTree(fields);
        return new EventEnvelope<>(UUID.randomUUID(), type, aggregate.toString(), Instant.now(), "test-trace", payload);
    }

    private static Jwt token(String token, UUID user, String role) {
        return Jwt.withTokenValue(token).header("alg", "RS256").subject(user.toString())
                .claim("roles", List.of(role)).claim("privileges", List.of("PRODUCT.LIST", "PRODUCT.DELETE")).build();
    }

    private static void admin(String statement) throws SQLException {
        try (var connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             var query = connection.createStatement()) {
            query.execute(statement);
        }
    }
}
