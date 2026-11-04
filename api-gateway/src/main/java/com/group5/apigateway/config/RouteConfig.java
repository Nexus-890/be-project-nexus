package com.group5.apigateway.config;

import com.group5.apigateway.ratelimit.RedisWindowRateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator routes(
            RouteLocatorBuilder builder,
            RedisWindowRateLimiter limiter,
            @Qualifier("clientIpKeyResolver") KeyResolver resolver) {
        // Chỉ giới hạn các route nghiệp vụ, không áp dụng cho Actuator/Swagger.
        // throwOnLimit chuyển 429 sang GatewayErrorHandler để trả JSON và Retry-After.
        return builder.routes()

                .route("user-service", route -> route
                        .path(
                                "/api/v1/auth/**",
                                "/api/v1/users/**",
                                "/.well-known/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://user-service"))

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
                                "/api/v1/orders/**"
                        )
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://commerce-service"))

                .route("payment-service", route -> route
                        .path("/api/v1/payments", "/api/v1/payments/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://payment-service"))

                .route("notification-service", route -> route
                        .path("/api/v1/notifications", "/api/v1/notifications/**",
                                "/api/v1/system/health", "/api/v1/system/health/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://notification-service"))

                .build();
    }

}
