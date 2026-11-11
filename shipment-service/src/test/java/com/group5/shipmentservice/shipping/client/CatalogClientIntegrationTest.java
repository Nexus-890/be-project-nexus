package com.group5.shipmentservice.shipping.client;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shipping.client.config.CatalogDeadlineOptions;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CatalogClientIntegrationTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class CatalogClientIntegrationTest {
    static final UUID SKU = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final CatalogHttpStub STUB = new CatalogHttpStub();

    @Autowired
    private CatalogClient catalog;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nexus.shipping.catalog-base-url", STUB::baseUrl);
    }

    @BeforeEach
    void setUp() {
        STUB.reset();
    }

    @AfterAll
    static void close() {
        STUB.close();
    }

    @Test
    void actualFeignProxyDecodesTypedEnvelopeAndDoesNotSendOptionsAsBody() {
        var response = catalog.getSkuById(SKU, options(1000));
        assertThat(response.code()).isZero();
        assertThat(response.data().id()).isEqualTo(SKU);
        assertThat(response.data().price()).isEqualByComparingTo("250000");
        assertThat(response.data().status()).isEqualTo("ACTIVE");
        assertThat(STUB.requests).containsExactly("GET /api/v1/skus/" + SKU + " ");
    }

    @Test
    void missingMessageKeepsSharedEnvelopeDefault() {
        STUB.body = "{\"code\":0,\"data\":{\"id\":\"" + SKU + "\",\"price\":250000,\"status\":\"ACTIVE\"}}";
        assertThat(catalog.getSkuById(SKU, options(1000)).message()).isEqualTo("response success");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "khong-phai-json", "{}", "{\"code\":0,\"data\":null}",
            "{\"code\":0,\"data\":{\"id\":\"11111111-1111-4111-8111-111111111111\",\"price\":\"250000\",\"status\":\"ACTIVE\"}}",
            "{\"code\":0,\"data\":{\"id\":\"khong-phai-uuid\",\"price\":250000,\"status\":\"ACTIVE\"}}",
            "{\"data\":{\"id\":\"11111111-1111-4111-8111-111111111111\",\"price\":250000,\"status\":\"ACTIVE\"}}"
    })
    void malformedResponseUsesDecodeFailureInsteadOfCoercingData(String body) {
        STUB.body = body;
        assertThatThrownBy(() -> catalog.getSkuById(SKU, options(1000))).isInstanceOf(DecodeException.class);
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void upstream503IsNotRetried() {
        STUB.status = 503;
        assertThatThrownBy(() -> catalog.getSkuById(SKU, options(1000)))
                .isInstanceOf(FeignException.class)
                .extracting(error -> ((FeignException) error).status()).isEqualTo(503);
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void timeoutBeforeHeadersIsBoundedAndNotRetried() {
        STUB.delayMs = 1000;
        long started = System.nanoTime();
        assertThatThrownBy(() -> catalog.getSkuById(SKU, options(180)))
                .isInstanceOf(FeignException.class).isNotInstanceOf(DecodeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(700);
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void stalledBodyIsCancelledByWholeCallTimeoutInsteadOfBecomingMalformedJson() {
        STUB.delayMs = 1000;
        STUB.delayBody = true;
        long started = System.nanoTime();
        assertThatThrownBy(() -> catalog.getSkuById(SKU, options(180)))
                .isInstanceOf(FeignException.class).isNotInstanceOf(DecodeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(700);
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void expiredDeadlineIsRejectedBeforeHttpCall() {
        ShippingDeadline deadline = ShippingDeadline.after(Duration.ofNanos(1));
        assertThatThrownBy(() -> catalog.getSkuById(SKU, new CatalogDeadlineOptions(deadline)))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo("SHIPMENT_CATALOG_UNAVAILABLE");
        assertThat(STUB.requests).isEmpty();
    }

    static CatalogDeadlineOptions options(long millis) {
        return new CatalogDeadlineOptions(ShippingDeadline.after(Duration.ofMillis(millis)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class
    })
    @EnableFeignClients(clients = CatalogClient.class)
    static class TestApplication {
    }
}

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CatalogDiscoveryClientIntegrationTest.TestApplication.class)
@TestPropertySource(properties = {
        "nexus.shipping.catalog-base-url=", "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false", "spring.cloud.compatibility-verifier.enabled=false",
        "eureka.client.enabled=false", "management.tracing.export.zipkin.enabled=false"
})
class CatalogDiscoveryClientIntegrationTest {
    private static final CatalogHttpStub STUB = new CatalogHttpStub();

    @Autowired
    private CatalogClient catalog;

    @Autowired
    private LoadBalancerClient loadBalancer;

    @Autowired
    private LoadBalancerClientFactory factory;

    @BeforeEach
    void setUp() {
        reset(loadBalancer);
        STUB.reset();
        factory.getProperties("catalog-service");
    }

    @AfterAll
    static void close() {
        STUB.close();
    }

    @Test
    void missingServiceInstanceReturns503WithoutCallingHttp() {
        assertThatThrownBy(() -> catalog.getSkuById(CatalogClientIntegrationTest.SKU, CatalogClientIntegrationTest.options(1000)))
                .isInstanceOf(FeignException.class)
                .extracting(error -> ((FeignException) error).status()).isEqualTo(503);
        assertThat(STUB.requests).isEmpty();
    }

    @Test
    void selectedDiscoveryInstanceIsUsedByFeignTransport() {
        var instance = new DefaultServiceInstance("catalog-1", "catalog-service", "127.0.0.1", STUB.port(), false);
        when(loadBalancer.choose(eq("catalog-service"), any())).thenReturn(instance);
        when(loadBalancer.reconstructURI(eq(instance), any())).thenAnswer(invocation -> {
            URI original = invocation.getArgument(1);
            return URI.create(STUB.baseUrl() + original.getPath());
        });
        var response = catalog.getSkuById(CatalogClientIntegrationTest.SKU, CatalogClientIntegrationTest.options(1000));
        assertThat(response.data().price()).isEqualByComparingTo("250000");
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void slowDiscoveryIsInterruptedWithinSharedDeadline() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(loadBalancer.choose(eq("catalog-service"), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> catalog.getSkuById(CatalogClientIntegrationTest.SKU, CatalogClientIntegrationTest.options(180)))
                .isInstanceOf(FeignException.class).isNotInstanceOf(DecodeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(700);
        assertThat(interrupted.await(500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(STUB.requests).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class
    })
    @EnableFeignClients(clients = CatalogClient.class)
    static class TestApplication {
        @Bean
        LoadBalancerClient loadBalancerClient() {
            return mock(LoadBalancerClient.class);
        }
    }
}

class CatalogHttpStub {
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile String body;
    volatile int status;
    volatile long delayMs;
    volatile boolean delayBody;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    CatalogHttpStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            try {
                if (!delayBody) {
                    Thread.sleep(delayMs);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                if (delayBody) {
                    exchange.getResponseBody().write(response, 0, 1);
                    exchange.getResponseBody().flush();
                    Thread.sleep(delayMs);
                    exchange.getResponseBody().write(response, 1, response.length - 1);
                } else {
                    exchange.getResponseBody().write(response);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        reset();
        server.start();
    }

    void reset() {
        requests.clear();
        body = "{\"code\":0,\"message\":\"response success\",\"data\":{\"id\":\""
                + CatalogClientIntegrationTest.SKU + "\",\"price\":250000,\"status\":\"ACTIVE\"}}";
        status = 200;
        delayMs = 0;
        delayBody = false;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
