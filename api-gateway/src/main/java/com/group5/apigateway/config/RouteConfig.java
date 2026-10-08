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
                                "/api/v1/skus/**")
                        .filters(filters -> filters.requestRateLimiter(config ->
                                config.setRateLimiter(limiter)
                                        .setKeyResolver(resolver)
                                        .setThrowOnLimit(true)))
                        .uri("lb://catalog-service"))

                //SAU NÀY TỰ THÊM NHA HIHI
                .build();

    }

}
