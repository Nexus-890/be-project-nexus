package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.ServiceHealthResponse;
import com.group5.notificationservice.shared.config.HealthProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ServiceHealthChecker implements DisposableBean {

    public static final String KEYCLOAK = "keycloak";

    private final DiscoveryClient discoveryClient;
    private final HealthProperties properties;
    private final RestClient restClient;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ServiceHealthChecker(DiscoveryClient discoveryClient, HealthProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.discoveryClient = discoveryClient;
        this.properties = properties;
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Mọi service, mọi instance và Keycloak được gọi song song trên virtual thread.
     * Tất cả dùng chung MỘT DEADLINE = {@code nexus.health.timeout}: hết deadline mà chưa có instance nào
     * trả 2xx thì service đó là DOWN, nên API không chờ lâu hơn timeout dù service có nhiều instance chậm.
     */
    public List<ServiceHealthResponse> checkAll() {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        List<String> names = new ArrayList<>(properties.services());
        List<CompletableFuture<Boolean>> checks = new ArrayList<>();
        for (String service : properties.services()) {
            checks.add(CompletableFuture.supplyAsync(() -> discoveryClient.getInstances(service), executor)
                    .thenCompose(this::anyInstanceAnswers));
        }
        names.add(KEYCLOAK);
        checks.add(CompletableFuture.supplyAsync(() -> answers(keycloakDiscovery()), executor));

        List<ServiceHealthResponse> result = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) {
            result.add(new ServiceHealthResponse(names.get(i), upBefore(checks.get(i), deadline)));
        }
        return result;
    }

    // XONG NGAY KHI 1 INSTANCE TRẢ 2XX, KHÔNG CHỜ CÁC INSTANCE CÒN LẠI
    // CHỈ TRẢ false KHI MỌI INSTANCE ĐỀU ĐÃ TRẢ LỖI
    private CompletableFuture<Boolean> anyInstanceAnswers(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> anyUp = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(instances.size());
        for (ServiceInstance instance : instances) {
            CompletableFuture.supplyAsync(() -> answers(instance.getUri().resolve("/actuator/health")), executor)
                    .whenComplete((up, error) -> {
                        if (Boolean.TRUE.equals(up)) {
                            anyUp.complete(true);
                        } else if (remaining.decrementAndGet() == 0) {
                            anyUp.complete(false);
                        }
                    });
        }
        return anyUp;
    }

    // LỖI BẤT NGỜ HOẶC HẾT DEADLINE CHUNG CHỈ LÀM SERVICE NÀY DOWN, CÁC SERVICE KHÁC VẪN CÓ KẾT QUẢ (AC4)
    private HealthStatus upBefore(CompletableFuture<Boolean> check, long deadline) {
        try {
            long left = Math.max(0, deadline - System.nanoTime());
            return check.get(left, TimeUnit.NANOSECONDS) ? HealthStatus.UP : HealthStatus.DOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthStatus.DOWN;
        } catch (ExecutionException | TimeoutException e) {
            return HealthStatus.DOWN;
        }
    }

    private URI keycloakDiscovery() {
        return URI.create(properties.keycloakRealmUrl() + "/.well-known/openid-configuration");
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

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
