package com.group5.shipmentservice.shipping.controller;

import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentExceptionHandler;
import com.group5.shipmentservice.shared.exception.ShipmentSecurityProblemAdvice;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.ghn.GhnAdapter;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.service.CarrierService;
import com.group5.shipmentservice.shipping.service.ShippingAddressService;
import com.group5.shipmentservice.shipping.service.ShippingCatalogService;
import com.group5.shipmentservice.shipping.service.ShippingQuoteService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = ShippingResilienceEvidenceTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.shipping.carrier=GHN",
        "nexus.shipping.api-timeout-ms=1000",
        "nexus.shipping.retry-limit=2",
        "nexus.shipping.retry-backoff-ms=10",
        "nexus.shipping.breaker-failure-threshold=1",
        "nexus.shipping.breaker-open-ms=30000",
        "nexus.shipping.free-shipping-threshold=1000000",
        "nexus.shipping.default-warehouse-id=11111111-1111-4111-8111-111111111111",
        "nexus.shipping.default-pickup.province-code=1000001",
        "nexus.shipping.default-pickup.ward-code=1003646",
        "nexus.shipping.default-pickup.address-line=Điểm lấy hàng fixture",
        "nexus.security.public-endpoints[0].method=GET",
        "nexus.security.public-endpoints[0].path=/api/v1/shipping/provinces",
        "nexus.security.public-endpoints[1].method=GET",
        "nexus.security.public-endpoints[1].path=/api/v1/shipping/wards"
})
class ShippingResilienceEvidenceTest {
    private static final String QUOTES_PATH = "/api/v1/shipping/quotes";
    private static final int INSTRUMENTATION_TOLERANCE_MS = 100;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ShippingProperties properties;

    @Autowired
    private GhnStub stub;

    @MockitoBean
    private ShippingCatalogService catalog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("250000"));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void timeoutReturns503WithRetriesThenOpenBreakerRejectsNextQuoteWithoutUpstreamCalls() throws Exception {
        mvc.perform(get("/api/v1/shipping/provinces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("1000001"));
        mvc.perform(get("/api/v1/shipping/wards").param("provinceCode", "1000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("1003646"));
        mvc.perform(post(QUOTES_PATH).header("Authorization", "Bearer evidence-fixture")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].carrier").value("GHN"))
                .andExpect(jsonPath("$.data[0].serviceType").value("STANDARD"))
                .andExpect(jsonPath("$.data[0].fee").value(27500))
                .andExpect(jsonPath("$.data[0].estimatedDeliveryAt").value("2026-11-15T16:59:59Z"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        System.out.println("AC01_STUB | HTTP=200 | carrier=GHN | serviceType=STANDARD | fee=27500 | ETA=ISO-8601 | không gọi GHN thật");

        stub.previewRequests.set(0);
        stub.delayPreview = true;
        long started = System.nanoTime();
        mvc.perform(post(QUOTES_PATH).header("Authorization", "Bearer evidence-fixture")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SHIPMENT_CARRIER_UNAVAILABLE"))
                .andExpect(jsonPath("$.instance").value(QUOTES_PATH))
                .andExpect(jsonPath("$.data").doesNotExist());
        long timeoutElapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        int timeoutAttempts = stub.previewRequests.get();
        System.out.printf("AC02_STUB | configuredBudgetMs=%d | instrumentationToleranceMs=%d | elapsedMs=%d | withinConfiguredBudget=%s | previewAttempts=%d | HTTP=503 | code=SHIPMENT_CARRIER_UNAVAILABLE%n",
                properties.apiTimeoutMs(), INSTRUMENTATION_TOLERANCE_MS, timeoutElapsedMs,
                timeoutElapsedMs <= properties.apiTimeoutMs(), timeoutAttempts);
        assertThat(timeoutAttempts).isEqualTo(properties.retryLimit() + 1);
        assertThat(timeoutElapsedMs).isLessThanOrEqualTo(properties.apiTimeoutMs() + INSTRUMENTATION_TOLERANCE_MS);

        int masterRequestsBefore = stub.masterRequests.get();
        clearInvocations(catalog);
        started = System.nanoTime();
        mvc.perform(post(QUOTES_PATH).header("Authorization", "Bearer evidence-fixture")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SHIPMENT_CIRCUIT_OPEN"));
        long failFastElapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("AC03_STUB | elapsedMs=%d | previewAttemptsBefore=%d | previewAttemptsAfter=%d | HTTP=503 | code=SHIPMENT_CIRCUIT_OPEN%n",
                failFastElapsedMs, timeoutAttempts, stub.previewRequests.get());
        assertThat(failFastElapsedMs).isLessThan(150);
        assertThat(stub.previewRequests).hasValue(timeoutAttempts);
        assertThat(stub.masterRequests).hasValue(masterRequestsBefore);
        verifyNoInteractions(catalog);
        mvc.perform(get("/api/v1/carriers").header("Authorization", "Bearer evidence-fixture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("GHN"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true))
                .andExpect(jsonPath("$.data[0].available").value(false));
    }

    @Test
    void warmedMasterDataSupportsQuoteAndRejectsMismatchedWardWithoutUpstreamReload() throws Exception {
        mvc.perform(get("/api/v1/shipping/provinces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("1000001"));
        mvc.perform(get("/api/v1/shipping/wards").param("provinceCode", "1000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("1003646"));
        int masterRequestsAfterWarmup = stub.masterRequests.get();
        assertThat(masterRequestsAfterWarmup).isEqualTo(2);
        stub.masterUnavailable = true;

        mvc.perform(post(QUOTES_PATH).header("Authorization", "Bearer evidence-fixture")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].carrier").value("GHN"))
                .andExpect(jsonPath("$.data[0].serviceType").value("STANDARD"))
                .andExpect(jsonPath("$.data[0].fee").value(27500));
        assertThat(stub.masterRequests).hasValue(masterRequestsAfterWarmup);
        assertThat(stub.previewRequests).hasValue(1);
        clearInvocations(catalog);
        String mismatched = request().replace("\"wardCode\":\"1003646\"", "\"wardCode\":\"1003354\"");

        mvc.perform(post(QUOTES_PATH).header("Authorization", "Bearer evidence-fixture")
                        .contentType(MediaType.APPLICATION_JSON).content(mismatched))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_ADDRESS"));
        assertThat(stub.masterRequests).hasValue(masterRequestsAfterWarmup);
        assertThat(stub.previewRequests).hasValue(1);
        verifyNoInteractions(catalog);
        System.out.printf("MASTER_CACHE_STUB | masterRequestsAfterWarmup=%d | masterRequestsAfterQuote=%d | previewRequests=%d | validQuoteHTTP=200 | mismatchedWardHTTP=400 | không gọi GHN thật%n",
                masterRequestsAfterWarmup, stub.masterRequests.get(), stub.previewRequests.get());
    }

    private String request() {
        return """
                {
                  "toAddress":{"provinceCode":"1000001","wardCode":"1003646","line1":"Địa chỉ giao hàng fixture","recipientName":"Người nhận fixture","recipientPhone":"0901234567"},
                  "items":[{"skuId":"22222222-2222-4222-8222-222222222222","quantity":1}],
                  "parcel":{"weightGrams":1200,"lengthCm":30,"widthCm":20,"heightCm":15}
                }
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @EnableConfigurationProperties(ShippingProperties.class)
    @Import({CarrierRegistry.class, CarrierExecutor.class, CarrierService.class, CarrierController.class,
            ShippingAddressService.class, ShippingQuoteService.class, ShippingController.class,
            ShipmentSecurityProblemAdvice.class, ShipmentExceptionHandler.class})
    static class TestApplication {
        @Bean(destroyMethod = "close")
        GhnStub ghnStub() throws IOException {
            return new GhnStub();
        }

        @Bean
        GhnAdapter ghnAdapter(GhnStub stub, ShippingProperties properties, ObjectMapper mapper) {
            return new GhnAdapter(new GhnProperties(stub.baseUrl(), 12345,
                    1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, 250, 300000), properties,
                    new MockEnvironment().withProperty("GHN_TOKEN", "token-chi-danh-cho-http-stub"), mapper);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!"evidence-fixture".equals(token)) {
                    throw new BadJwtException("Token fixture không hợp lệ");
                }
                Instant now = Instant.now();
                return Jwt.withTokenValue(token).header("alg", "RS256")
                        .subject("33333333-3333-4333-8333-333333333333")
                        .issuedAt(now.minusSeconds(30)).expiresAt(now.plusSeconds(300))
                        .claim("roles", List.of("BUYER"))
                        .claim("privileges", List.of("SHIPPING.QUOTE")).build();
            };
        }
    }

    static class GhnStub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers;
        private final AtomicInteger previewRequests = new AtomicInteger();
        private final AtomicInteger masterRequests = new AtomicInteger();
        private volatile boolean delayPreview;
        private volatile boolean masterUnavailable;

        GhnStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            workers = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(workers);
            server.createContext("/", this::handle);
            server.start();
        }

        URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (masterUnavailable && path.startsWith("/shiip/public-api/v3/master-data/")) {
                masterRequests.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String data;
            if ("/shiip/public-api/v3/master-data/province/all".equals(path)) {
                masterRequests.incrementAndGet();
                data = "[{\"_id\":1000001,\"name\":\"Hồ Chí Minh\",\"type\":\"province\",\"parent_id\":1,\"status\":1}]";
            } else if ("/shiip/public-api/v3/master-data/ward/all-by-province-id".equals(path)) {
                masterRequests.incrementAndGet();
                data = "[{\"_id\":1003646,\"name\":\"Phường Vũng Tàu\",\"type\":\"ward\",\"parent_id\":1000001,\"status\":1}]";
            } else if ("/shiip/public-api/v2/shipping-order/preview".equals(path)) {
                previewRequests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                data = "{\"order_code\":\"\",\"total_fee\":27500,\"expected_delivery_time\":\"2026-11-15T16:59:59Z\"}";
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = ("{\"code\":200,\"message\":\"Success\",\"data\":" + data + "}")
                    .getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                if (delayPreview && path.endsWith("/preview")) {
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    Thread.sleep(1500);
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } else {
                    exchange.getResponseBody().write(body);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
