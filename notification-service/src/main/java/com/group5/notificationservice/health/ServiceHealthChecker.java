package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.ServiceHealthResponse;
import com.group5.notificationservice.shared.config.HealthProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    public ServiceHealthChecker(DiscoveryClient discoveryClient, HealthProperties properties) {
        this.discoveryClient = discoveryClient;
        this.properties = properties;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.timeout()).executor(executor).build();
    }

    /**
     * Mọi service, mọi instance và Keycloak được gọi song song trên virtual thread.
     * Tất cả dùng chung MỘT DEADLINE = {@code nexus.health.timeout}: hết deadline mà chưa có instance nào
     * trả 2xx thì service đó là DOWN, nên API không chờ lâu hơn timeout dù service có nhiều instance chậm.
     */
    public List<ServiceHealthResponse> checkAll() {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        Probes probes = new Probes();
        try {
            List<String> names = new ArrayList<>(properties.services());
            List<CompletableFuture<Boolean>> checks = new ArrayList<>();
            for (String service : properties.services()) {
                checks.add(CompletableFuture.supplyAsync(() -> discoveryClient.getInstances(service), executor)
                        .thenCompose(instances -> anyInstanceAnswers(instances, probes)));
            }
            names.add(KEYCLOAK);
            checks.add(probes.answers(keycloakDiscovery()));

            List<ServiceHealthResponse> result = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                result.add(new ServiceHealthResponse(names.get(i), upBefore(checks.get(i), deadline)));
            }
            return result;
        } finally {
            probes.cancelAll();
        }
    }

    // XONG NGAY KHI 1 INSTANCE TRẢ 2XX, KHÔNG CHỜ CÁC INSTANCE CÒN LẠI
    // CHỈ TRẢ false KHI MỌI INSTANCE ĐỀU ĐÃ TRẢ LỖI
    private CompletableFuture<Boolean> anyInstanceAnswers(List<ServiceInstance> instances, Probes probes) {
        if (instances.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> anyUp = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(instances.size());
        for (ServiceInstance instance : instances) {
            probes.answers(instance.getUri().resolve("/actuator/health")).whenComplete((up, error) -> {
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

    @Override
    public void destroy() {
        http.shutdownNow();
        executor.shutdownNow();
    }

    // GIỮ MỌI REQUEST CỦA 1 LẦN checkAll. XONG HOẶC HẾT DEADLINE THÌ HỦY HẾT REQUEST CÒN CHẠY
    // -> SERVER TRẢ TỪNG BYTE CHẬM CŨNG KHÔNG ĐỂ LẠI SOCKET/PROBE CHẠY NỀN SAU KHI API ĐÃ TRẢ DOWN
    private final class Probes {

        private final List<CompletableFuture<?>> calls = new ArrayList<>();
        private boolean cancelled;

        // KHÔNG TRẢ 2XX (CONNECTION REFUSED, TIMEOUT, 4XX/5XX, BỊ HỦY) ĐỀU TÍNH LÀ KHÔNG TRẢ LỜI
        synchronized CompletableFuture<Boolean> answers(URI uri) {
            if (cancelled) {
                return CompletableFuture.completedFuture(false);
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.timeout()).GET().build();
            CompletableFuture<HttpResponse<Void>> call =
                    http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            calls.add(call);
            return call.handle((response, error) -> error == null && response.statusCode() / 100 == 2);
        }

        synchronized void cancelAll() {
            cancelled = true;
            calls.forEach(call -> call.cancel(true));
        }
    }
}
