package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.ServiceHealthResponse;
import com.group5.notificationservice.shared.config.HealthProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ServiceHealthChecker {

    public static final String KEYCLOAK = "keycloak";

    private final DiscoveryClient discoveryClient;
    private final HealthProperties properties;
    private final RestClient restClient;

    public ServiceHealthChecker(DiscoveryClient discoveryClient, HealthProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.discoveryClient = discoveryClient;
        this.properties = properties;
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Kiểm tra song song mọi service trong {@code nexus.health.services}, mỗi service chạy trên một
     * virtual thread (Java 21), rồi thêm Keycloak ở cuối danh sách. Service không có trong Eureka
     * hoặc không trả lời 2xx trong {@code nexus.health.timeout} thì là DOWN.
     */
    public List<ServiceHealthResponse> checkAll() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ServiceHealthResponse>> checks = new ArrayList<>();
            for (String service : properties.services()) {
                checks.add(CompletableFuture.supplyAsync(() -> checkService(service), executor)
                        // Lỗi bất ngờ chỉ làm service này DOWN, các service khác vẫn trả kết quả bình thường (AC4).
                        .exceptionally(error -> new ServiceHealthResponse(service, HealthStatus.DOWN)));
            }
            checks.add(CompletableFuture.supplyAsync(this::checkKeycloak, executor)
                    .exceptionally(error -> new ServiceHealthResponse(KEYCLOAK, HealthStatus.DOWN)));
            return checks.stream().map(CompletableFuture::join).toList();
        }
    }

    private ServiceHealthResponse checkService(String service) {
        boolean up = discoveryClient.getInstances(service).stream()
                .anyMatch(instance -> answers(instance.getUri().resolve("/actuator/health")));
        return new ServiceHealthResponse(service, up ? HealthStatus.UP : HealthStatus.DOWN);
    }

    private ServiceHealthResponse checkKeycloak() {
        URI discovery = URI.create(properties.keycloakRealmUrl() + "/.well-known/openid-configuration");
        return new ServiceHealthResponse(KEYCLOAK, answers(discovery) ? HealthStatus.UP : HealthStatus.DOWN);
    }

    // Connection refused, timeout và status 4xx/5xx (ví dụ 503) đều ném RestClientException.
    // Mọi trường hợp không trả 2xx đều tính là không trả lời.
    private boolean answers(URI uri) {
        try {
            return restClient.get().uri(uri).retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            return false;
        }
    }
}
