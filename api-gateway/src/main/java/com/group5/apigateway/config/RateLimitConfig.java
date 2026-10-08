package com.group5.apigateway.config;

import com.group5.apigateway.ratelimit.RedisWindowRateLimiter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    @RefreshScope
    @ConfigurationProperties(prefix = "nexus.gateway.rate-limit")
    public RateLimitProperties rateLimitProperties() {
        return new RateLimitProperties();
    }

    @Bean
    @Primary
    public KeyResolver clientIpKeyResolver() {
        return exchange -> {
            var remoteAddress = exchange.getRequest().getRemoteAddress();

            if (remoteAddress == null || remoteAddress.getAddress() == null) {
                return Mono.empty();
            }

            return Mono.just(
                    remoteAddress.getAddress().getHostAddress()
            );
        };
    }

    @Bean
    @Primary
    public RedisWindowRateLimiter ipRateLimiter(
            ReactiveStringRedisTemplate redis,
            RateLimitProperties properties) {

        return new RedisWindowRateLimiter(redis, properties);
    }
}
