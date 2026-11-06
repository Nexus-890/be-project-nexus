package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.ServiceHealthResponse;
import com.group5.notificationservice.shared.config.HealthProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceHealthCheckerTest {

    private static final URI STOPPED = URI.create("http://127.0.0.1:1");
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final Duration WITHIN_DEADLINE = TIMEOUT.plusMillis(500);

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ServiceHealthChecker> checkers = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private DiscoveryClient discoveryClient;

    @BeforeEach
    void setup() {
        discoveryClient = mock(DiscoveryClient.class);
    }

    @AfterEach
    void stopServers() {
        release.countDown();
        checkers.forEach(ServiceHealthChecker::destroy);
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void reportsEveryConfiguredServiceAndKeycloak() throws IOException {
        register("payment-service", serve(200));
        register("inventory-service", serve(200));
        register("shipment-service", serve(200));
        register("rating-service", serve(200));

        List<ServiceHealthResponse> result = checker(serve(200),
                "payment-service", "inventory-service", "shipment-service", "rating-service").checkAll();

        assertThat(result).containsExactly(
                new ServiceHealthResponse("payment-service", HealthStatus.UP),
                new ServiceHealthResponse("inventory-service", HealthStatus.UP),
                new ServiceHealthResponse("shipment-service", HealthStatus.UP),
                new ServiceHealthResponse("rating-service", HealthStatus.UP),
                new ServiceHealthResponse(ServiceHealthChecker.KEYCLOAK, HealthStatus.UP));
    }

    @Test
    void aStoppedServiceIsDownWithoutFailingTheOthers() throws IOException {
        register("user-service", serve(200));
        register("catalog-service", STOPPED);
        when(discoveryClient.getInstances("commerce-service")).thenReturn(List.of());

        List<ServiceHealthResponse> result = checker(STOPPED,
                "user-service", "catalog-service", "commerce-service").checkAll();

        assertThat(result).containsExactly(
                new ServiceHealthResponse("user-service", HealthStatus.UP),
                new ServiceHealthResponse("catalog-service", HealthStatus.DOWN),
                new ServiceHealthResponse("commerce-service", HealthStatus.DOWN),
                new ServiceHealthResponse(ServiceHealthChecker.KEYCLOAK, HealthStatus.DOWN));
    }

    @Test
    void aServiceAnsweringWithAnErrorIsDown() throws IOException {
        register("rating-service", serve(503));

        List<ServiceHealthResponse> result = checker(serve(200), "rating-service").checkAll();

        assertThat(result).first().isEqualTo(new ServiceHealthResponse("rating-service", HealthStatus.DOWN));
    }

    @Test
    void threeSlowInstancesShareOneDeadlineInsteadOfOneTimeoutEach() throws IOException {
        register("catalog-service", slow(), slow(), slow());
        ServiceHealthChecker checker = checker(serve(200), "catalog-service");

        long started = System.nanoTime();
        List<ServiceHealthResponse> result = checker.checkAll();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).first().isEqualTo(new ServiceHealthResponse("catalog-service", HealthStatus.DOWN));
        assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT.minusMillis(50)).isLessThan(WITHIN_DEADLINE);
    }

    @Test
    void oneHealthyInstanceIsEnoughWithoutWaitingForTheSlowOnes() throws IOException {
        register("catalog-service", slow(), slow(), serve(200));
        ServiceHealthChecker checker = checker(serve(200), "catalog-service");

        long started = System.nanoTime();
        List<ServiceHealthResponse> result = checker.checkAll();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).first().isEqualTo(new ServiceHealthResponse("catalog-service", HealthStatus.UP));
        assertThat(elapsed).isLessThan(TIMEOUT);
    }

    @Test
    void everyServiceAndKeycloakSlowStillFinishesWithinOneDeadline() throws IOException {
        String[] services = IntStream.rangeClosed(1, 9).mapToObj(i -> "service-" + i).toArray(String[]::new);
        for (String service : services) {
            register(service, slow(), slow(), slow());
        }
        ServiceHealthChecker checker = checker(slow(), services);

        long started = System.nanoTime();
        List<ServiceHealthResponse> result = checker.checkAll();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).hasSize(services.length + 1)
                .allSatisfy(service -> assertThat(service.status()).isEqualTo(HealthStatus.DOWN));
        assertThat(elapsed).isLessThan(WITHIN_DEADLINE);
    }

    private ServiceHealthChecker checker(URI keycloak, String... services) {
        HealthProperties properties = new HealthProperties(List.of(services), keycloak.resolve("/realms/nexus"),
                TIMEOUT, "test");
        ServiceHealthChecker checker = new ServiceHealthChecker(discoveryClient, properties);
        checkers.add(checker);
        return checker;
    }

    private void register(String service, URI... uris) {
        List<ServiceInstance> instances = Arrays.stream(uris)
                .map(uri -> (ServiceInstance) new DefaultServiceInstance(service + "-" + uri.getPort(), service,
                        uri.getHost(), uri.getPort(), false))
                .toList();
        when(discoveryClient.getInstances(service)).thenReturn(instances);
    }

    private URI serve(int status) throws IOException {
        return start(status, false);
    }

    // INSTANCE NHẬN KẾT NỐI NHƯNG KHÔNG TRẢ LỜI CHO ĐẾN KHI TEST KẾT THÚC
    private URI slow() throws IOException {
        return start(200, true);
    }

    private URI start(int status, boolean hang) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            if (hang) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
