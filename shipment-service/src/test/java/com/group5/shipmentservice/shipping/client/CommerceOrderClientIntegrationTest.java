package com.group5.shipmentservice.shipping.client;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shipping.client.config.CommerceDeadlineOptions;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
@ContextConfiguration(classes = CommerceOrderClientIntegrationTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class CommerceOrderClientIntegrationTest {
    static final UUID ORDER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID BUYER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final String AUTHORIZATION = "Bearer staff-token-for-http-fixture";
    private static final CommerceHttpStub STUB = new CommerceHttpStub();

    @Autowired CommerceOrderClient commerce;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nexus.shipping.commerce-base-url", STUB::baseUrl);
    }

    @BeforeEach
    void setUp() { STUB.reset(); }

    @AfterAll
    static void close() { STUB.close(); }

    @Test
    void realFeignProxyRelaysOnlyExplicitCallerHeaderAndDecodesTypedPaidSnapshot() {
        var response = commerce.getPaidOrder(ORDER, AUTHORIZATION, options(1000));
        assertThat(response.code()).isZero();
        assertThat(response.data().orderId()).isEqualTo(ORDER);
        assertThat(response.data().buyerId()).isEqualTo(BUYER);
        assertThat(response.data().shippingFee()).isEqualByComparingTo("30000.00");
        assertThat(response.data().paidAt().toInstant()).hasToString("2026-11-11T00:00:00Z");
        assertThat(response.data().warehouseId()).isNull();
        assertThat(response.data().shippingInfo()).containsEntry("parcel", null);
        assertThat(STUB.requests).containsExactly(new CommerceHttpStub.SeenRequest(
                "GET", "/internal/v1/orders/" + ORDER + "/shipping-snapshot", AUTHORIZATION, ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "0.01", "30000.25", "100000000000000.01", "99999999999999999.99"})
    void shippingFeeKeepsEveryDecimalDigitFromTheOriginalHttpBody(String shippingFee) {
        // Replace the JSON number as text: building a tree here could round the fixture itself.
        STUB.body = STUB.body.replace("30000.00", shippingFee);

        var response = commerce.getPaidOrder(ORDER, AUTHORIZATION, options(1000));

        assertThat(response.data().shippingFee()).isEqualByComparingTo(shippingFee);
    }

    @Test
    void eachCallUsesItsOwnAuthorizationWithoutRetainingThePreviousCaller() {
        commerce.getPaidOrder(ORDER, AUTHORIZATION, options(1000));
        commerce.getPaidOrder(ORDER, "Bearer second-staff-fixture", options(1000));
        assertThat(STUB.requests).extracting(CommerceHttpStub.SeenRequest::authorization)
                .containsExactly(AUTHORIZATION, "Bearer second-staff-fixture");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 409, 503})
    void upstreamErrorsKeepTheirStatusForDomainMappingAndAreNeverRetried(int status) {
        STUB.status = status;
        STUB.body = "{\"type\":\"urn:problem:domain-error\",\"status\":" + status
                + ",\"code\":\"COMMERCE_ORDER_ERROR\"}";
        assertThatThrownBy(() -> commerce.getPaidOrder(ORDER, AUTHORIZATION, options(1000)))
                .isInstanceOf(FeignException.class)
                .extracting(error -> ((FeignException) error).status()).isEqualTo(status);
        assertThat(STUB.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "{}", "{\"code\":0,\"data\":null}",
            "{\"orderId\":\"11111111-1111-4111-8111-111111111111\"}"
    })
    void malformedOrUnwrappedSuccessIsRejected(String body) {
        STUB.body = body;
        assertThatThrownBy(() -> commerce.getPaidOrder(ORDER, AUTHORIZATION, options(1000)))
                .isInstanceOf(DecodeException.class);
        assertThat(STUB.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orderVersion", "paidAt", "shippingFee", "shippingInfo", "orderId", "buyerId"})
    void trustedSnapshotMetadataCannotBeSilentlyMissing(String field) {
        var mapper = JsonMapper.shared();
        var root = mapper.readTree(STUB.body);
        ((ObjectNode) root.path("data")).remove(field);
        STUB.body = mapper.writeValueAsString(root);
        assertThatThrownBy(() -> commerce.getPaidOrder(ORDER, AUTHORIZATION, options(1000)))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void redirectIsNotFollowedWithTheStaffBearerHeader() {
        STUB.status = 302;
        assertThatThrownBy(() -> commerce.getPaidOrder(ORDER, AUTHORIZATION, options(1000)))
                .isInstanceOf(FeignException.class)
                .extracting(error -> ((FeignException) error).status()).isEqualTo(302);
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void responseHeaderTimeoutIsBoundedWithoutRetry() {
        STUB.delayMs = 1000;
        long started = System.nanoTime();
        assertThatThrownBy(() -> commerce.getPaidOrder(ORDER, AUTHORIZATION, options(180)))
                .isInstanceOf(FeignException.class).isNotInstanceOf(DecodeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(700);
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void stalledResponseBodyIsCancelledByTheWholeCallDeadline() {
        STUB.delayMs = 1000;
        STUB.delayBody = true;
        long started = System.nanoTime();
        assertThatThrownBy(() -> commerce.getPaidOrder(ORDER, AUTHORIZATION, options(180)))
                .isInstanceOf(FeignException.class).isNotInstanceOf(DecodeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(700);
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void expiredBudgetFailsBeforeSendingAuthorizationOrCallingHttp() {
        var deadline = ShippingDeadline.after(Duration.ofNanos(1));
        assertThatThrownBy(() -> commerce.getPaidOrder(ORDER, AUTHORIZATION, new CommerceDeadlineOptions(deadline)))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo("SHIPMENT_COMMERCE_UNAVAILABLE");
        assertThat(STUB.requests).isEmpty();
    }

    static CommerceDeadlineOptions options(long millis) {
        return new CommerceDeadlineOptions(ShippingDeadline.after(Duration.ofMillis(millis)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @EnableFeignClients(clients = CommerceOrderClient.class)
    static class TestApplication { }
}

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CommerceDiscoveryClientIntegrationTest.TestApplication.class)
@TestPropertySource(properties = {
        "nexus.shipping.commerce-base-url=", "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false", "spring.cloud.compatibility-verifier.enabled=false",
        "eureka.client.enabled=false", "management.tracing.export.zipkin.enabled=false"
})
class CommerceDiscoveryClientIntegrationTest {
    private static final CommerceHttpStub STUB = new CommerceHttpStub();

    @Autowired CommerceOrderClient commerce;
    @Autowired LoadBalancerClient loadBalancer;
    @Autowired LoadBalancerClientFactory factory;

    @BeforeEach
    void setUp() {
        reset(loadBalancer);
        STUB.reset();
        factory.getProperties("commerce-service");
    }

    @AfterAll
    static void close() { STUB.close(); }

    @Test
    void unavailableDiscoveryInstanceFailsClosedWithoutHttp() {
        assertThatThrownBy(() -> commerce.getPaidOrder(CommerceOrderClientIntegrationTest.ORDER,
                CommerceOrderClientIntegrationTest.AUTHORIZATION, CommerceOrderClientIntegrationTest.options(1000)))
                .isInstanceOf(FeignException.class)
                .extracting(error -> ((FeignException) error).status()).isEqualTo(503);
        assertThat(STUB.requests).isEmpty();
    }

    @Test
    void discoveredCommerceInstanceReceivesTheExplicitCallerBearer() {
        var instance = new DefaultServiceInstance("commerce-1", "commerce-service", "127.0.0.1", STUB.port(), false);
        when(loadBalancer.choose(eq("commerce-service"), any())).thenReturn(instance);
        when(loadBalancer.reconstructURI(eq(instance), any())).thenAnswer(invocation -> {
            URI original = invocation.getArgument(1);
            return URI.create(STUB.baseUrl() + original.getPath());
        });
        var response = commerce.getPaidOrder(CommerceOrderClientIntegrationTest.ORDER,
                CommerceOrderClientIntegrationTest.AUTHORIZATION, CommerceOrderClientIntegrationTest.options(1000));
        assertThat(response.data().orderId()).isEqualTo(CommerceOrderClientIntegrationTest.ORDER);
        assertThat(STUB.requests).extracting(CommerceHttpStub.SeenRequest::authorization)
                .containsExactly(CommerceOrderClientIntegrationTest.AUTHORIZATION);
    }

    @Test
    void slowDiscoveryCannotConsumeMoreThanTheSharedDeadline() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(loadBalancer.choose(eq("commerce-service"), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> commerce.getPaidOrder(CommerceOrderClientIntegrationTest.ORDER,
                CommerceOrderClientIntegrationTest.AUTHORIZATION, CommerceOrderClientIntegrationTest.options(180)))
                .isInstanceOf(FeignException.class).isNotInstanceOf(DecodeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(700);
        assertThat(interrupted.await(500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(STUB.requests).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @EnableFeignClients(clients = CommerceOrderClient.class)
    static class TestApplication {
        @Bean
        LoadBalancerClient loadBalancerClient() { return mock(LoadBalancerClient.class); }
    }
}

class CommerceHttpStub {
    record SeenRequest(String method, String path, String authorization, String body) { }
    final List<SeenRequest> requests = new CopyOnWriteArrayList<>();
    volatile String body;
    volatile int status;
    volatile long delayMs;
    volatile boolean delayBody;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    CommerceHttpStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            requests.add(new SeenRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            try {
                if (!delayBody) Thread.sleep(delayMs);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (status == 302) exchange.getResponseHeaders().set("Location", baseUrl() + "/unexpected-redirect");
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
        body = """
                {"code":0,"message":"response success","data":{
                  "orderId":"11111111-1111-4111-8111-111111111111",
                  "buyerId":"22222222-2222-4222-8222-222222222222",
                  "orderVersion":1,"status":"PAID","paidAt":"2026-11-11T00:00:00Z",
                  "warehouseId":null,"shippingFee":30000.00,
                  "shippingInfo":{"parcel":null,"pickupAddress":null}
                }}
                """;
        status = 200;
        delayMs = 0;
        delayBody = false;
    }

    String baseUrl() { return "http://127.0.0.1:" + port(); }
    int port() { return server.getAddress().getPort(); }

    void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
