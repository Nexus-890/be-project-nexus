package com.group5.apigateway.ratelimit;

import com.group5.apigateway.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class RedisWindowRateLimiter extends AbstractRateLimiter<RateLimitProperties> {

    private static final Logger log =
            LoggerFactory.getLogger(RedisWindowRateLimiter.class);

    private static final long WINDOW_MILLIS = 60_000;
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(200);
    private static final String KEY_PREFIX = "nexus:rate-limit:ip:";

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final RedisScript<List<Long>> script;

    // Tránh log WARN cho mọi request trong cùng một outage.
    private final AtomicBoolean redisUnavailable = new AtomicBoolean();

    public RedisWindowRateLimiter(
            ReactiveStringRedisTemplate redis,
            RateLimitProperties properties) {

        super(RateLimitProperties.class, "nexus.gateway.rate-limit", null);

        this.redis = redis;
        this.properties = properties;
        this.script = createScript();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisScript<List<Long>> createScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/rate-limit.lua"));
        script.setResultType(List.class);

        return (RedisScript<List<Long>>) (RedisScript<?>) script;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        return Mono.defer(() -> {
            // Đọc mỗi request, không giữ snapshot limit từ lúc startup.
            int limit = properties.getRequestsPerMinute();

            // Không đưa routeId vào key: quota dùng chung theo IP.
            String key = KEY_PREFIX + id;

            return redis.execute(
                            script,
                            List.of(key),
                            Integer.toString(limit),
                            Long.toString(WINDOW_MILLIS))
                    .single()
                    // Chặn cả trường hợp Redis nhận kết nối nhưng không trả lời.
                    .timeout(REDIS_TIMEOUT)
                    .map(result -> {
                        if (result.size() != 3) {
                            throw new IllegalStateException(
                                    "Unexpected Redis rate-limit result");
                        }

                        if (redisUnavailable.getAndSet(false)) {
                            log.info("Redis rate limiter recovered");
                        }

                        boolean allowed = result.get(0) == 1L;
                        long remaining = result.get(1);
                        long ttlMillis = result.get(2);

                        if (allowed) {
                            return new Response(true, Map.of(
                                    "X-RateLimit-Limit",
                                    Integer.toString(limit),
                                    "X-RateLimit-Remaining",
                                    Long.toString(remaining)));
                        }

                        // Retry-After tính bằng giây; làm tròn lên theo TTL còn lại.
                        long retryAfter = Math.max(
                                1, (ttlMillis + 999) / 1000);

                        return new Response(false, Map.of(
                                "X-RateLimit-Limit",
                                Integer.toString(limit),
                                "X-RateLimit-Remaining", "0",
                                HttpHeaders.RETRY_AFTER,
                                Long.toString(retryAfter)));
                    })
                    .onErrorResume(
                            error -> error instanceof DataAccessException
                                    || error instanceof TimeoutException,
                            error -> {
                                if (redisUnavailable.compareAndSet(false, true)) {
                                    log.warn(
                                            "Redis rate limiter unavailable; "
                                                    + "allowing requests (fail-open)",
                                            error);
                                }

                                return Mono.just(
                                        new Response(true, Map.of()));
                            });
        });
    }
}
