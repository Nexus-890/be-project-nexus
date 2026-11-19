package com.group5.auctionservice.auction;

import com.sun.net.httpserver.HttpServer;
import com.group5.auctionservice.TestPostgres;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/auction-catalog-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.open-in-view=false", "management.tracing.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false"
})
class AuctionCatalogIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID SKU = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final String IMAGE = "https://example.com/first.jpg";
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger AVAILABLE = new AtomicInteger();
    private static final AtomicInteger STOCK_CALLS = new AtomicInteger();
    private static final AtomicReference<CatalogReply> REPLY = new AtomicReference<>();
    private static final ExecutorService HTTP_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer CATALOG = startCatalog();

    static {
        POSTGRES.start();
    }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CircuitBreakerRegistry breakers;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("nexus.auction.catalog-base-url", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
        registry.add("nexus.auction.inventory-base-url", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
    }

    @BeforeEach
    void setup() {
        breakers.circuitBreaker("auctionCatalog").reset();
        jdbc.update("DELETE FROM auctions");
        jdbc.update("DELETE FROM outbox");
        CALLS.set(0);
        AVAILABLE.set(5);
        STOCK_CALLS.set(0);
        REPLY.set(new CatalogReply(200, productJson("""
                [{"imageUrl":"%s","sortOrder":0},{"imageUrl":"https://example.com/second.jpg","sortOrder":1}]
                """.formatted(IMAGE)), 0));
        when(decoder.decode("owner")).thenReturn(token("owner", OWNER));
        when(decoder.decode("other")).thenReturn(token("other", UUID.randomUUID()));
        mvc = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void stopCatalog() {
        CATALOG.stop(0);
        HTTP_THREADS.shutdownNow();
        POSTGRES.stop();
    }

    @Test
    void snapshotsCatalogTitleAndFirstImageInOneCall() throws Exception {
        create("owner", SKU, ",\"title\":\"Client title\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("Catalog product"))
                .andExpect(jsonPath("$.data.imageUrl").value(IMAGE));
        assertThat(jdbc.queryForMap("SELECT title, image_url, sku_code, seller_id FROM auctions"))
                .containsEntry("title", "Catalog product").containsEntry("image_url", IMAGE)
                .containsEntry("sku_code", "NX-CATALOG-1")
                .containsEntry("seller_id", OWNER);
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT event_type, payload->'payload'->>'skuId' AS sku FROM outbox"))
                .containsEntry("event_type", "AuctionCreated").containsEntry("sku", SKU.toString());
    }

    @Test
    void refusesZeroStockBeforeCreatingAuctionOrEvent() throws Exception {
        AVAILABLE.set(0);
        expectProblem(create("owner", SKU, ""), 409, "AUCTION_STOCK_UNAVAILABLE");
        assertThat(rowCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void refusesProductWithoutImageBeforeCallingInventoryOrWritingAuctionAndEvent() throws Exception {
        REPLY.set(new CatalogReply(200, productJson("[]"), 0));
        expectProblem(create("owner", SKU, ""), 409, "AUCTION_IMAGE_REQUIRED");
        assertThat(STOCK_CALLS.get()).isZero();
        assertThat(rowCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
    }

    @Test
    void rejectsOtherSellerAndSkuOutsideTheProductWithoutInserting() throws Exception {
        expectProblem(create("other", SKU, ""), 403, "AUCTION_FORBIDDEN");
        expectProblem(create("owner", UUID.randomUUID(), ""), 404, "AUCTION_SKU_NOT_FOUND");
        assertThat(rowCount()).isZero();
    }

    @Test
    void catalog404DoesNotOpenCircuit() throws Exception {
        REPLY.set(new CatalogReply(404, "{}", 0));
        for (int i = 0; i < 6; i++) {
            expectProblem(create("owner", SKU, ""), 404, "AUCTION_PRODUCT_NOT_FOUND");
        }
        assertThat(CALLS.get()).isEqualTo(6);
        assertThat(rowCount()).isZero();
    }

    @Test
    void circuitOpensAfterCatalogErrorsAndStopsDownstreamCalls() throws Exception {
        REPLY.set(new CatalogReply(500, "{}", 0));
        for (int i = 0; i < 5; i++) {
            expectProblem(create("owner", SKU, ""), 503, "AUCTION_CATALOG_UNAVAILABLE");
        }
        expectProblem(create("owner", SKU, ""), 503, "AUCTION_CATALOG_UNAVAILABLE");
        assertThat(CALLS.get()).isEqualTo(5);
        assertThat(rowCount()).isZero();
    }

    @Test
    void slowCatalogReturnsProblemWithinThreeSeconds() throws Exception {
        REPLY.set(new CatalogReply(200, productJson("[]"), 5000));
        long started = System.nanoTime();
        expectProblem(create("owner", SKU, ""), 503, "AUCTION_CATALOG_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(rowCount()).isZero();
    }

    private ResultActions create(String caller, UUID sku, String titleField) throws Exception {
        Instant start = Instant.now().plusSeconds(3600);
        return mvc.perform(post("/api/v1/auctions").header("Authorization", "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"productId":"%s","skuId":"%s","startingPrice":100000,
                         "startAt":"%s","endAt":"%s"%s}
                        """.formatted(PRODUCT, sku, start, start.plusSeconds(7200), titleField)));
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM auctions", Long.class);
    }

    private static void expectProblem(ResultActions response, int expectedStatus, String code) throws Exception {
        response.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static Jwt token(String value, UUID seller) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(seller.toString())
                .claim("roles", List.of("SELLER")).claim("privileges", List.of("AUCTION.CREATE")).build();
    }

    private static String productJson(String images) {
        return """
                {"code":0,"message":"response success","data":{"id":"%s","sellerId":"%s","name":"Catalog product",
                 "status":"ACTIVE","skus":[{"id":"%s","productId":"%s","skuCode":"NX-CATALOG-1"}],"images":%s}}
                """.formatted(PRODUCT, OWNER, SKU, PRODUCT, images);
    }

    private static HttpServer startCatalog() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HTTP_THREADS);
            server.createContext("/api/v1/inventory/" + SKU, exchange -> {
                try (exchange) {
                    STOCK_CALLS.incrementAndGet();
                    byte[] bytes = """
                            {"code":0,"message":"success","data":{"skuId":"%s","available":%d}}
                            """.formatted(SKU, AVAILABLE.get()).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.createContext("/api/v1/products/" + PRODUCT, exchange -> {
                try (exchange) {
                    CALLS.incrementAndGet();
                    CatalogReply reply = REPLY.get();
                    try {
                        Thread.sleep(reply.delayMs());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot start the catalog test server", error);
        }
    }

    private record CatalogReply(int status, String body, long delayMs) {}
}
