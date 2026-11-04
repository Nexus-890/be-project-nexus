package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.ServiceHealthResponse;
import com.group5.notificationservice.shared.config.HealthProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceHealthCheckerTest {

    private static final URI STOPPED = URI.create("http://127.0.0.1:1");

    private final List<HttpServer> servers = new ArrayList<>();
    private DiscoveryClient discoveryClient;

    @BeforeEach
    void setup() {
        discoveryClient = mock(DiscoveryClient.class);
    }

    @AfterEach
    void stopServers() {
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

    private ServiceHealthChecker checker(URI keycloak, String... services) {
        HealthProperties properties = new HealthProperties(List.of(services), keycloak.resolve("/realms/nexus"),
                Duration.ofSeconds(1));
        return new ServiceHealthChecker(discoveryClient, properties);
    }

    private void register(String service, URI uri) {
        when(discoveryClient.getInstances(service)).thenReturn(List.of(
                new DefaultServiceInstance(service + "-1", service, uri.getHost(), uri.getPort(), false)));
    }

    private URI serve(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
