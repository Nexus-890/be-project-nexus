package com.group5.commerceservice.order.client.config;

import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.Decoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded Catalog, Inventory and Shipping calls; private costs use a machine token. */
public class OrderFeignConfiguration {
    @Bean(destroyMethod = "close")
    public OrderHttpClient orderHttpClient(LoadBalancerClient loadBalancer, LoadBalancerClientFactory factory,
                                           ObjectProvider<LoadBalancerFeignRequestTransformer> transformers,
                                           @Value("${nexus.order.remote-timeout-ms:1800}") long timeoutMillis) {
        return new OrderHttpClient(loadBalancer, factory, transformers.orderedStream().toList(), timeoutMillis);
    }

    @Bean
    public Retryer orderRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Decoder orderResponseDecoder(ObjectMapper mapper) {
        return new OrderResponseDecoder(mapper);
    }

    @Bean
    public Request.Options orderRequestOptions(@Value("${nexus.order.remote-timeout-ms:1800}") long timeoutMillis) {
        return new Request.Options(timeoutMillis, TimeUnit.MILLISECONDS, timeoutMillis, TimeUnit.MILLISECONDS, false);
    }

    @Bean
    public RequestInterceptor orderBearerRelay(CatalogCostTokenProvider tokens) {
        return template -> {
            if (template.path().equals("/internal/v1/catalog/sku-costs")) {
                template.removeHeader(HttpHeaders.AUTHORIZATION);
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken());
            } else if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
                // Đọc trên request thread trước khi transport dùng virtual thread, không lưu token trong singleton.
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.getToken().getTokenValue());
            }
        };
    }

    @org.springframework.stereotype.Component
    public static class CatalogCostTokenProvider {
        private final String tokenUri;
        private final String clientId;
        private final String clientSecret;
        private final Duration timeout;
        private final Clock clock;
        private final HttpClient http;
        private String cachedToken;
        private Instant refreshAt = Instant.MIN;

        public CatalogCostTokenProvider(
                @Value("${nexus.order.catalog-costs.token-uri:http://localhost:18080/realms/nexus/protocol/openid-connect/token}") String tokenUri,
                @Value("${nexus.order.catalog-costs.client-id:nexus-commerce-service}") String clientId,
                @Value("${nexus.order.catalog-costs.client-secret:}") String clientSecret,
                @Value("${nexus.order.catalog-costs.token-timeout-ms:1500}") long timeoutMillis,
                org.springframework.beans.factory.ObjectProvider<java.time.Clock> clocks) {
            if (timeoutMillis < 1 || timeoutMillis > 30000) {
                throw new IllegalArgumentException("Token timeout must be between 1 and 30000 milliseconds");
            }
            this.tokenUri = tokenUri;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.timeout = Duration.ofMillis(timeoutMillis);
            this.clock = clocks.getIfAvailable(Clock::systemUTC);
            this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        }

        public synchronized String accessToken() {
            Instant now = clock.instant();
            if (cachedToken != null && now.isBefore(refreshAt)) {
                return cachedToken;
            }
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw unavailable();
            }
            try {
                URI uri = URI.create(tokenUri);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null) {
                    throw unavailable();
                }
                String form = "grant_type=client_credentials&client_id=" + encode(clientId)
                        + "&client_secret=" + encode(clientSecret);
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(OrderRequestDeadline.remainingMillis(timeout.toMillis())))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200 || response.body().length() > 65536) {
                    throw unavailable();
                }
                JsonNode body = JsonMapper.shared().readTree(response.body());
                JsonNode token = body == null ? null : body.get("access_token");
                JsonNode expires = body == null ? null : body.get("expires_in");
                JsonNode type = body == null ? null : body.get("token_type");
                if (body == null || !body.isObject() || token == null || !token.isString()
                        || token.stringValue().isBlank() || token.stringValue().chars().anyMatch(Character::isWhitespace)
                        || type == null || !type.isString() || !"Bearer".equalsIgnoreCase(type.stringValue())
                        || expires == null || !expires.isIntegralNumber() || !expires.canConvertToLong()
                        || expires.longValue() < 1 || expires.longValue() > 86400) {
                    throw unavailable();
                }
                long lifetime = expires.longValue();
                refreshAt = now.plusSeconds(lifetime).minusMillis(Math.min(30000, lifetime * 100));
                if (!clock.instant().isBefore(refreshAt)) {
                    throw unavailable();
                }
                cachedToken = token.stringValue();
                return cachedToken;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (Exception failure) {
                throw unavailable();
            }
        }

        public synchronized void invalidate(String token) {
            if (token != null && token.equals(cachedToken)) {
                cachedToken = null;
                refreshAt = Instant.MIN;
            }
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        private static com.group5.common_core.exception.DomainException unavailable() {
            return new com.group5.common_core.exception.DomainException(com.group5.commerceservice.shared.exception.CommerceErrorCode.CATALOG_UNAVAILABLE);
        }
    }

}
