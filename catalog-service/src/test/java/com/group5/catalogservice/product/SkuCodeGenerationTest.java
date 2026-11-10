package com.group5.catalogservice.product;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/catalog-sku-test.properties",
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
class SkuCodeGenerationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final UUID SELLER = UUID.randomUUID();
    static { POSTGRES.start(); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private UUID productId;
    private String prefix;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        productId = UUID.randomUUID();
        prefix = "SKU-" + productId.toString().replace("-", "") + "-";
        jdbc.update("""
                INSERT INTO products (id, category_id, seller_id, name, slug, status)
                VALUES (?, '69000000-0000-4000-8000-000000000102', ?, 'SKU code fixture', ?, 'ACTIVE')
                """, productId, SELLER, "sku-code-test-" + productId);
        when(decoder.decode("seller")).thenReturn(Jwt.withTokenValue("seller").header("alg", "RS256")
                .subject(SELLER.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("SELLER")).claim("privileges", List.of("PRODUCT.UPDATE")).build());
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM skus WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void twoSkusOnAProductWithNoneGetNumbers01And02AndASentSkuCodeIsIgnored() throws Exception {
        String first = create("{\"skuCode\":\"MY-OWN-CODE\",\"color\":\"Black\",\"size\":\"M\",\"price\":150000}");
        String second = create("{\"color\":\"Black\",\"size\":\"L\",\"price\":160000}");

        assertThat(first).isEqualTo(prefix + "01");
        assertThat(second).isEqualTo(prefix + "02");
        assertThat(codesInDatabase()).containsExactly(prefix + "01", prefix + "02");
    }

    @Test
    void parallelCreatesOnOneProductAllReturn201WithDifferentCodes() throws Exception {
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 1; i <= requests; i++) {
                String body = "{\"color\":\"Black\",\"size\":\"S" + i + "\",\"price\":100000}";
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/products/{id}/skus", productId)
                            .header("Authorization", "Bearer seller")
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
                }));
            }
            start.countDown();
            List<String> codes = new ArrayList<>();
            for (Future<MvcResult> result : results) {
                MvcResult response = result.get(30, TimeUnit.SECONDS);
                assertThat(response.getResponse().getStatus()).isEqualTo(201);
                codes.add(code(response));
            }
            List<String> expected = IntStream.rangeClosed(1, requests)
                    .mapToObj(n -> prefix + String.format("%02d", n)).toList();
            assertThat(codes).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(codesInDatabase()).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void updatingColorSizeOrPriceKeepsTheSkuCode() throws Exception {
        String created = create("{\"color\":\"Black\",\"size\":\"M\",\"price\":150000}");
        UUID skuId = jdbc.queryForObject("SELECT id FROM skus WHERE sku_code = ?", UUID.class, created);

        MvcResult updated = mvc.perform(put("/api/v1/skus/{id}", skuId).header("Authorization", "Bearer seller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuCode\":\"CHANGED\",\"color\":\"White\",\"size\":\"XL\",\"price\":175000}"))
                .andExpect(status().isOk()).andReturn();

        assertThat(code(updated)).isEqualTo(created);
        assertThat(jdbc.queryForMap("SELECT sku_code, color, size, price FROM skus WHERE id = ?", skuId))
                .containsEntry("sku_code", created).containsEntry("color", "White").containsEntry("size", "XL")
                .containsEntry("price", new java.math.BigDecimal("175000.00"));
    }

    @Test
    void numberingContinuesAfterTheHighestGeneratedCodeAndSkipsTypedCodes() throws Exception {
        jdbc.update("INSERT INTO skus (product_id, sku_code, color, size, price) VALUES (?, ?, 'Red', 'M', 100000)",
                productId, prefix + "07");
        jdbc.update("INSERT INTO skus (product_id, sku_code, color, size, price) VALUES (?, ?, 'Red', 'L', 100000)",
                productId, "SHOE-BLACK-" + productId);

        assertThat(create("{\"color\":\"Blue\",\"size\":\"M\",\"price\":120000}")).isEqualTo(prefix + "08");
    }

    private String create(String body) throws Exception {
        return code(mvc.perform(post("/api/v1/products/{id}/skus", productId).header("Authorization", "Bearer seller")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn());
    }

    private static String code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).path("data").path("skuCode").asString();
    }

    private List<String> codesInDatabase() {
        return jdbc.queryForList("SELECT sku_code FROM skus WHERE product_id = ? ORDER BY sku_code",
                String.class, productId);
    }
}
