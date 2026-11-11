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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/catalog-price-range-test.properties",
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
class ProductPriceRangeTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String CATEGORY = "69000000-0000-4000-8000-000000000102";
    private static final UUID SELLER = UUID.randomUUID();
    static { POSTGRES.start(); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private UUID productId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        productId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, category_id, seller_id, name, slug, status)
                VALUES (?, CAST(? AS uuid), ?, 'Price range fixture', ?, 'ACTIVE')
                """, productId, CATEGORY, SELLER, "price-range-test-" + productId);
        when(decoder.decode("seller")).thenReturn(Jwt.withTokenValue("seller").header("alg", "RS256")
                .subject(SELLER.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("SELLER")).claim("privileges", List.of("PRODUCT.LIST")).build());
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM skus WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void aProductWithSkusAt100000And150000ReturnsMinPrice100000AndMaxPrice150000OnEveryRead() throws Exception {
        sku("M", 150000);
        sku("L", 100000);

        assertRange(fromList(get("/api/v1/products").param("size", "100")), "100000.00", "150000.00");
        assertRange(fromList(get("/api/v1/products").param("categoryId", CATEGORY).param("size", "100")),
                "100000.00", "150000.00");
        assertRange(fromList(get("/api/v1/products/mine").param("size", "100")), "100000.00", "150000.00");
        assertRange(read(get("/api/v1/products/{id}", productId)).path("data"), "100000.00", "150000.00");
    }

    @Test
    void aProductWhoseSkusShareOnePriceReturnsTheSameMinAndMax() throws Exception {
        sku("M", 120000);
        sku("L", 120000);

        assertRange(read(get("/api/v1/products/{id}", productId)).path("data"), "120000.00", "120000.00");
    }

    @Test
    void aProductWithoutSkusReturnsNullMinAndMax() throws Exception {
        JsonNode detail = read(get("/api/v1/products/{id}", productId)).path("data");

        assertThat(detail.has("minPrice")).isTrue();
        assertThat(detail.has("maxPrice")).isTrue();
        assertThat(detail.path("minPrice").isNull()).isTrue();
        assertThat(detail.path("maxPrice").isNull()).isTrue();
    }

    private void sku(String size, int price) {
        jdbc.update("INSERT INTO skus (product_id, sku_code, color, size, price) VALUES (?, ?, 'Black', ?, ?)",
                productId, "SKU-" + productId.toString().replace("-", "") + "-" + size, size, price);
    }

    private JsonNode fromList(MockHttpServletRequestBuilder request) throws Exception {
        for (JsonNode product : read(request).path("data")) {
            if (product.path("id").asString().equals(productId.toString())) {
                return product;
            }
        }
        throw new AssertionError("product " + productId + " is not in the list");
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request.header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static void assertRange(JsonNode product, String min, String max) {
        assertThat(product.path("minPrice").decimalValue()).isEqualByComparingTo(min);
        assertThat(product.path("maxPrice").decimalValue()).isEqualByComparingTo(max);
    }
}
