package com.group5.commerceservice.order.client.config;

import feign.Client;
import feign.Request;
import feign.Response;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Giới hạn cả chọn instance, kết nối và đọc hết body; không để timeout treo transaction checkout. */
public final class OrderHttpClient implements Client, AutoCloseable {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;
    private final Client delegate;
    private final long timeoutMillis;

    public OrderHttpClient(LoadBalancerClient loadBalancer, LoadBalancerClientFactory factory,
                           List<LoadBalancerFeignRequestTransformer> transformers, long timeoutMillis) {
        this(timeoutMillis, loadBalancer, factory, transformers);
    }

    /** Dùng transport thật nhưng không discovery trong HTTP contract tests. */
    public OrderHttpClient(long timeoutMillis) {
        this(timeoutMillis, null, null, List.of());
    }

    private OrderHttpClient(long timeoutMillis, LoadBalancerClient loadBalancer, LoadBalancerClientFactory factory,
                            List<LoadBalancerFeignRequestTransformer> transformers) {
        if (timeoutMillis < 1 || timeoutMillis > 2200) {
            throw new IllegalArgumentException("Order remote timeout must be between 1 and 2200 milliseconds");
        }
        this.timeoutMillis = timeoutMillis;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        Client transport = this::send;
        Client discovered = loadBalancer == null ? transport
                : new FeignBlockingLoadBalancerClient(transport, loadBalancer, factory, transformers);
        this.delegate = (request, options) -> Set.of("inventory-service", "shipment-service")
                .contains(URI.create(request.url()).getHost())
                ? discovered.execute(request, options) : transport.execute(request, options);
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        long remainingMillis = OrderRequestDeadline.remainingMillis(timeoutMillis);
        Request.Options bounded = new Request.Options(remainingMillis, TimeUnit.MILLISECONDS,
                remainingMillis, TimeUnit.MILLISECONDS, false);
        var pending = executor.submit(() -> delegate.execute(request, bounded));
        try {
            return pending.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            pending.cancel(true);
            throw new IOException("Order dependency timed out", error);
        } catch (InterruptedException error) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Order dependency request interrupted", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException failure) throw failure;
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new IOException("Order dependency request failed", error.getCause());
        }
    }

    private Response send(Request request, Request.Options options) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(Duration.ofMillis(options.readTimeoutMillis()));
        Set<String> restricted = Set.of("connection", "content-length", "expect", "host", "upgrade");
        request.headers().forEach((name, values) -> {
            if (!restricted.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        builder.method(request.httpMethod().name(), request.body() == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            java.util.Map<String, java.util.Collection<String>> headers = new java.util.HashMap<>();
            response.headers().map().forEach(headers::put);
            return Response.builder().request(request).status(response.statusCode()).reason("")
                    .headers(headers).body(response.body()).build();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Order dependency request interrupted", error);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        http.shutdownNow();
    }
}
