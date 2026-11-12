package com.group5.shipmentservice.shipping.client.config;

import com.group5.shipmentservice.shipping.client.CommerceFeignHttpClient;
import feign.Request;
import feign.Response;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class CommerceLoadBalancerClient extends FeignBlockingLoadBalancerClient {
    private final CommerceFeignHttpClient transport;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CommerceLoadBalancerClient(CommerceFeignHttpClient transport, LoadBalancerClient loadBalancer,
            LoadBalancerClientFactory factory, List<LoadBalancerFeignRequestTransformer> transformers) {
        super(transport, loadBalancer, factory, transformers);
        this.transport = transport;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        if (!(options instanceof CommerceDeadlineOptions budget)) return super.execute(request, options);
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicReference<Response> received = new AtomicReference<>();
        var pending = executor.submit(() -> {
            Response response = super.execute(request, options);
            received.set(response);
            if (abandoned.get() || Thread.currentThread().isInterrupted() || budget.deadline().timedOut()) {
                closeResponse(received);
                throw new IOException("Commerce request deadline exceeded");
            }
            return response;
        });
        try {
            Response response = pending.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
            received.compareAndSet(response, null);
            return response;
        } catch (TimeoutException error) {
            abandoned.set(true);
            closeResponse(received);
            pending.cancel(true);
            throw new IOException("Commerce request deadline exceeded", error);
        } catch (InterruptedException error) {
            abandoned.set(true);
            closeResponse(received);
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Commerce request interrupted", error);
        } catch (ExecutionException error) {
            closeResponse(received);
            if (error.getCause() instanceof IOException failure) throw failure;
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new IOException("Cannot call commerce", error.getCause());
        } catch (RuntimeException error) {
            abandoned.set(true);
            closeResponse(received);
            pending.cancel(true);
            throw error;
        }
    }

    private static void closeResponse(AtomicReference<Response> received) {
        Response response = received.getAndSet(null);
        if (response != null) response.close();
    }

    public void close() {
        executor.shutdownNow();
        transport.close();
    }
}
