package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

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

    @Test
    void stagingForwardedHeaderTransformationKeepsTwoProxyClientsDistinct() throws Exception {
        var transformer = new ForwardedHeaderTransformer();
        var proxy = new InetSocketAddress(InetAddress.getByName("172.20.0.2"), 12345);
        for (String address : new String[]{"198.51.100.10", "198.51.100.11"}) {
            var request = MockServerHttpRequest.get("http://gateway/api/v1/products")
                    .remoteAddress(proxy)
                    // nginx overwrites these values from the connection, tested in nginx-smoke.mjs.
                    .header("X-Forwarded-For", address)
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", "staging.example.test")
                    .build();
            var transformed = transformer.apply(request);
            var exchange = MockServerWebExchange.from(request).mutate().request(transformed).build();
            assertThat(config.clientIpKeyResolver().resolve(exchange).block()).isEqualTo(address);
            assertThat(transformed.getURI().getScheme()).isEqualTo("https");
            assertThat(transformed.getHeaders().getFirst("X-Forwarded-For")).isNull();
        }
    }

    @Test
    void stagingForwardedIpv6AddressIsParsedWithoutDns() throws Exception {
        var request = MockServerHttpRequest.get("http://gateway/api/v1/products")
                .remoteAddress(new InetSocketAddress(InetAddress.getByName("172.20.0.2"), 12345))
                .header("X-Forwarded-For", "2001:db8::25")
                .build();
        var transformed = new ForwardedHeaderTransformer().apply(request);
        var exchange = MockServerWebExchange.from(request).mutate().request(transformed).build();
        assertThat(config.clientIpKeyResolver().resolve(exchange).block())
                .isEqualTo(InetAddress.getByName("2001:db8::25").getHostAddress());
    }
}
