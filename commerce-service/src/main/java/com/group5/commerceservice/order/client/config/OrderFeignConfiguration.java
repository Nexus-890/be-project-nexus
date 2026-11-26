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
import java.util.List;
import java.util.Map;
import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
            if (template.path().startsWith("/internal/v1/catalog/skus/")) {
                template.removeHeader(HttpHeaders.AUTHORIZATION);
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken());
            } else if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
                // Đọc trên request thread trước khi transport dùng virtual thread, không lưu token trong singleton.
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.getToken().getTokenValue());
            }
        };
    }

    @Bean
    public CatalogCostTokenProvider catalogCostTokens(OrderHttpClient transport,
            @Value("${nexus.security.jwt.issuer:http://localhost:18080/realms/nexus}") String issuer,
            @Value("${nexus.order.catalog-costs.client-secret:}") String secret) {
        return new CatalogCostTokenProvider(transport, issuer, secret);
    }

    /** Uses the existing bounded transport, issuer and timeout; only the private credential is separate. */
    public static class CatalogCostTokenProvider {
        private final OrderHttpClient transport;
        private final String tokenUri;
        private final String secret;
        private String cachedToken;
        private long expiresAt;

        public CatalogCostTokenProvider(OrderHttpClient transport, String issuer, String secret) {
            this.transport = transport;
            this.tokenUri = issuer.replaceAll("/+$", "") + "/protocol/openid-connect/token";
            this.secret = secret;
        }

        public synchronized String accessToken() {
            long started = System.nanoTime();
            if (cachedToken != null && started < expiresAt) return cachedToken;
            if (secret == null || secret.isBlank()) throw unavailable();
            String form = "grant_type=client_credentials&client_id=nexus-commerce-service&client_secret="
                    + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            var request = Request.create(Request.HttpMethod.POST, tokenUri,
                    Map.of("Content-Type", List.of("application/x-www-form-urlencoded"),
                            "Accept", List.of("application/json")), form.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, new feign.RequestTemplate());
            try (var response = transport.execute(request, new Request.Options())) {
                if (response.status() != 200 || response.body() == null) throw unavailable();
                byte[] bytes = response.body().asInputStream().readAllBytes();
                if (bytes.length > 65536) throw unavailable();
                var body = JsonMapper.shared().readTree(bytes);
                var token = body.path("access_token");
                var lifetime = body.path("expires_in");
                if (!token.isString() || token.asString().isBlank() || token.asString().chars().anyMatch(Character::isWhitespace)
                        || !"Bearer".equalsIgnoreCase(body.path("token_type").asString())
                        || !lifetime.isIntegralNumber() || !lifetime.canConvertToLong()
                        || lifetime.longValue() < 1 || lifetime.longValue() > 86400) throw unavailable();
                expiresAt = started + Duration.ofSeconds(lifetime.longValue())
                        .minusMillis(Math.min(30000, lifetime.longValue() * 100)).toNanos();
                if (System.nanoTime() >= expiresAt) throw unavailable();
                cachedToken = token.asString();
                return cachedToken;
            } catch (java.io.IOException | RuntimeException failure) {
                throw unavailable();
            }
        }

        private static DomainException unavailable() { return new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE); }
    }
}
