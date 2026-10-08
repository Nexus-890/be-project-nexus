package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void resolvesClientKeyFromSocketRemoteAddressInsteadOfXForwardedFor() throws Exception {
        InetAddress socketAddress = InetAddress.getByName("127.0.0.1");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .remoteAddress(new InetSocketAddress(socketAddress, 54321))
                        .header("X-Forwarded-For", "203.0.113.99")
                        .build());

        String key = config.clientIpKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("127.0.0.1");
    }

    @Test
    void returnsEmptyKeyWhenSocketAddressCannotBeResolved() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .remoteAddress(InetSocketAddress.createUnresolved("client.local", 54321))
                        .build());

        String key = config.clientIpKeyResolver().resolve(exchange).block();

        assertThat(key).isNull();
    }
}
