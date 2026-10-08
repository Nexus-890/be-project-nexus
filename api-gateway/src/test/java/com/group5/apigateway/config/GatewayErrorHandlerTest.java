package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    @Test
    void returnsProblemJsonAndRetryAfterForTooManyRequests() {
        HttpHeaders sourceHeaders = new HttpHeaders();
        sourceHeaders.set(HttpHeaders.RETRY_AFTER, "42");
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                sourceHeaders,
                new byte[0],
                StandardCharsets.UTF_8);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products").build());
        GatewayErrorHandler handler = new GatewayErrorHandler(ServerCodecConfigurer.create());

        handler.handle(exchange, exception).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("42");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"status\":429")
                .contains("Too Many Requests")
                .contains("/api/products");
    }

    @Test
    void returnsServiceUnavailableProblemJsonWhenDownstreamConnectionFails() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products").build());
        GatewayErrorHandler handler = new GatewayErrorHandler(ServerCodecConfigurer.create());

        handler.handle(exchange, new IllegalStateException(new java.net.ConnectException("refused")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"status\":503")
                .contains("Service Unavailable");
    }
}
