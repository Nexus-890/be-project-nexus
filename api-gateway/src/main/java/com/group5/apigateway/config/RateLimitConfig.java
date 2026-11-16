package com.group5.apigateway.config;

import com.group5.apigateway.ratelimit.RedisWindowRateLimiter;
import io.netty.util.NetUtil;
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
    // Refresh thay cấu hình quota, không xóa counter đang có trong Redis.
    @RefreshScope
    @ConfigurationProperties(prefix = "nexus.gateway.rate-limit")
    public RateLimitProperties rateLimitProperties() {
        return new RateLimitProperties();
    }

    @Bean
    @Primary
    public KeyResolver clientIpKeyResolver() {
        return exchange -> {
            // Read the request remote address, never a raw client header.
            // STAGING enables Spring's transformer behind nginx, which overwrites forwarding headers.
            var remoteAddress = exchange.getRequest().getRemoteAddress();

            if (remoteAddress == null) {
                return Mono.empty();
            }

            // ForwardedHeaderTransformer uses an unresolved socket address.
            // Parse numeric literals without DNS; retain fail-closed for hostnames.
            var address = remoteAddress.getAddress();
            if (address == null) {
                address = NetUtil.createInetAddressFromIpAddressString(remoteAddress.getHostString());
            }
            if (address == null) {
                return Mono.empty();
            }

            return Mono.just(
                    address.getHostAddress()
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
