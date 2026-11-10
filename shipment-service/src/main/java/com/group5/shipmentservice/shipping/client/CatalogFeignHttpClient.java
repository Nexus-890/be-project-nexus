package com.group5.shipmentservice.shipping.client;

import com.group5.shipmentservice.shipping.client.config.CatalogDeadlineOptions;
import feign.Client;
import feign.Request;
import feign.Response;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class CatalogFeignHttpClient implements Client {
    private final OkHttpClient client;

    public CatalogFeignHttpClient() {
        this.client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        Duration remaining = options instanceof CatalogDeadlineOptions budget
                ? budget.remaining() : Duration.ofMillis(options.readTimeoutMillis());
        long millis = Math.max(1, remaining.toMillis());
        OkHttpClient scoped = client.newBuilder()
                .callTimeout(millis, TimeUnit.MILLISECONDS)
                .connectTimeout(millis, TimeUnit.MILLISECONDS)
                .readTimeout(millis, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS)
                .build();
        Request.Options scopedOptions = new Request.Options(remaining, remaining, false);
        return new feign.okhttp.OkHttpClient(scoped).execute(request, scopedOptions);
    }

    public void close() {
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
    }
}
