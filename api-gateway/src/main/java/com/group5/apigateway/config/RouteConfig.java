package com.group5.apigateway.config;

import com.group5.apigateway.ratelimit.RedisWindowRateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import static com.group5.apigateway.ratelimit.RedisWindowRateLimiter.AUCTION_READ_ROUTE_ID;

@Configuration
public class RouteConfig {

    @Bean
    // Định tuyến API tới service sở hữu và áp dụng giới hạn tần suất tại gateway.
    public RouteLocator routes(
            RouteLocatorBuilder builder,
            RedisWindowRateLimiter limiter,
            @Qualifier("clientIpKeyResolver") KeyResolver resolver,
            @Value("${nexus.services.config-url:http://localhost:8888}") String configUrl) {
        // Chỉ giới hạn các route nghiệp vụ, không áp dụng cho Actuator/Swagger.
        // throwOnLimit chuyển 429 sang GatewayErrorHandler để trả JSON và Retry-After.
        return builder.routes()

                .route("user-service", route -> route
                        .path(
                                "/api/v1/auth/**",
                                "/api/v1/users/**",
                                "/api/v1/roles", "/api/v1/roles/**", "/api/v1/permissions",
                                "/api/v1/sellers/**",
                                "/.well-known/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://user-service"))

                .route("system-config-service", route -> route
                        .path("/api/v1/system/configs", "/api/v1/system/configs/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter).setKeyResolver(resolver).setThrowOnLimit(true)))
                        .uri(configUrl))

                .route("catalog-service", route -> route
                        .path(
                                "/api/v1/categories/**",
                                "/api/v1/products/**",
                                "/api/v1/skus/**"
                        )
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://catalog-service"))

                .route("commerce-service", route -> route
                        .path(
                                "/api/v1/carts/**",
                                "/api/v1/checkout/**",
                                "/api/v1/orders/**",
                                "/api/v1/promotions/**",
                                "/api/v1/vouchers",
                                "/api/v1/vouchers/**"
                        )
                        // PAY-01 moved to POST /payments (#121); do not forward its obsolete commerce path.
                        .and().not(oldPayment -> oldPayment.path("/api/v1/orders/*/payment"))
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://commerce-service"))

                .route("notification-service", route -> route
                        .path("/api/v1/notifications", "/api/v1/notifications/**",
                                "/api/v1/event-logs", "/api/v1/event-logs/**",
                                "/api/v1/audit-logs", "/api/v1/audit-logs/**",
                                "/api/v1/system/health", "/api/v1/system/health/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://notification-service"))

                .route("payment-service", route -> route
                        .path("/api/v1/payments/**", "/api/v1/refunds/**",
                                "/api/v1/webhooks/sepay")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://payment-service"))

                .route("shipment-service", route -> route
                        .path("/api/v1/shipments/**", "/api/v1/shipping/**",
                                "/api/v1/carriers/**", "/api/v1/webhooks/carriers/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://shipment-service"))

                .route("rating-service", route -> route
                        .path("/api/v1/ratings/**", "/api/v1/reputations/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://rating-service"))

                .route("inventory-service", route -> route
                        .path("/api/v1/inventory/**", "/api/v1/warehouses/**")
                        .and().not(settle -> settle.path("/api/v1/inventory/reservations/*/confirm",
                                "/api/v1/inventory/reservations/*/release"))
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://inventory-service"))

                .route(AUCTION_READ_ROUTE_ID, route -> route
                        .order(-1)
                        .path("/api/v1/auctions/*/state", "/api/v1/auctions/*/bids")
                        .and().method(HttpMethod.GET)
                        .filters(filters -> filters.requestRateLimiter(config -> config
                                .setRateLimiter(limiter)
                                .setKeyResolver(resolver)
                                .setThrowOnLimit(true)))
                        .uri("lb://auction-service"))

                .route("auction-service", route -> route
                        .path("/api/v1/auctions", "/api/v1/auctions/**", "/api/v1/bids", "/api/v1/bids/**")
                        .filters(filters -> filters.requestRateLimiter(config -> config
                                .setRateLimiter(limiter)
                                .setKeyResolver(resolver)
                                .setThrowOnLimit(true)))
                        .uri("lb://auction-service"))

                .build();
    }

}
