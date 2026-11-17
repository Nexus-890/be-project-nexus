package com.group5.paymentservice.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Component
@ConditionalOnProperty(name = "nexus.payout.provider", havingValue = "http")
public class HttpPayoutGateway implements PayoutGateway {
    private final RestClient client;

    public HttpPayoutGateway(PayoutProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(properties.getProviderTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.getProviderTimeout());
        client = RestClient.builder().baseUrl(properties.getProviderUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.getProviderToken()).build();
    }

    public String provider() {
        return "http";
    }

    public boolean simulated() {
        return false;
    }

    public Result send(Transfer transfer) {
        return client.post().uri("/transfers").header("Idempotency-Key", transfer.key())
                .body(transfer).retrieve().body(Result.class);
    }

    public Result lookup(String key) {
        return client.get().uri("/transfers/by-key/{key}", key).retrieve().body(Result.class);
    }
}
